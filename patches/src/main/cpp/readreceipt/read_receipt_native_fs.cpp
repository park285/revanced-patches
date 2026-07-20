#include "read_receipt_native_fs.h"

#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE (1U << 0)
#endif

namespace {

class ScopedUtfChars {
public:
    ScopedUtfChars(JNIEnv* environment, jstring value)
            : environment_(environment), value_(value), chars_(nullptr) {
        if (value_ != nullptr) chars_ = environment_->GetStringUTFChars(value_, nullptr);
    }

    ~ScopedUtfChars() {
        if (chars_ != nullptr) environment_->ReleaseStringUTFChars(value_, chars_);
    }

    ScopedUtfChars(const ScopedUtfChars&) = delete;
    ScopedUtfChars& operator=(const ScopedUtfChars&) = delete;

    const char* get() const { return chars_; }

private:
    JNIEnv* environment_;
    jstring value_;
    const char* chars_;
};

class ScopedFd {
public:
    explicit ScopedFd(int descriptor) : descriptor_(descriptor) {}
    ~ScopedFd() {
        if (descriptor_ >= 0) close(descriptor_);
    }

    ScopedFd(const ScopedFd&) = delete;
    ScopedFd& operator=(const ScopedFd&) = delete;

    int get() const { return descriptor_; }

private:
    int descriptor_;
};

bool valid_identity(jlong device, jlong inode) {
    return device >= 0 && inode > 0;
}

bool valid_path(const char* path) {
    return path != nullptr && path[0] != '\0';
}

bool valid_basename(const char* basename) {
    if (!valid_path(basename)) return false;
    if ((basename[0] == '.' && basename[1] == '\0')
            || (basename[0] == '.' && basename[1] == '.' && basename[2] == '\0')) {
        return false;
    }
    for (const char* cursor = basename; *cursor != '\0'; ++cursor) {
        if (*cursor == '/') return false;
    }
    return true;
}

bool valid_temp_basename(const char* basename) {
    constexpr char prefix[] = ".iris_read_receipts.db.tmp-";
    constexpr std::size_t suffix_size = 32;
    if (!valid_basename(basename)
            || std::strlen(basename) != sizeof(prefix) - 1 + suffix_size
            || std::memcmp(basename, prefix, sizeof(prefix) - 1) != 0) {
        return false;
    }
    for (std::size_t index = sizeof(prefix) - 1; index < sizeof(prefix) - 1 + suffix_size;
            ++index) {
        const char value = basename[index];
        if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) return false;
    }
    return true;
}

int errno_result(int error) {
    return error == ENOENT ? READ_RECEIPT_FS_NOT_FOUND : READ_RECEIPT_FS_IO_ERROR;
}

bool exact_regular(const struct stat& status, jlong device, jlong inode) {
    return S_ISREG(status.st_mode)
            && static_cast<std::uint64_t>(status.st_dev)
                    == static_cast<std::uint64_t>(device)
            && static_cast<std::uint64_t>(status.st_ino)
                    == static_cast<std::uint64_t>(inode);
}

int verify_fd(int descriptor, jlong device, jlong inode) {
    struct stat status{};
    if (fstat(descriptor, &status) != 0) return errno_result(errno);
    return exact_regular(status, device, inode)
            ? READ_RECEIPT_FS_OK
            : READ_RECEIPT_FS_IDENTITY_MISMATCH;
}

bool exact_single_link_regular(
        const struct stat& status, jlong device, jlong inode) {
    return exact_regular(status, device, inode) && status.st_nlink == 1;
}

int verify_single_link_fd(int descriptor, jlong device, jlong inode) {
    struct stat status{};
    if (fstat(descriptor, &status) != 0) return errno_result(errno);
    return exact_single_link_regular(status, device, inode)
            ? READ_RECEIPT_FS_OK
            : READ_RECEIPT_FS_IDENTITY_MISMATCH;
}

constexpr int publish_errno_result(int error) {
    if (error == EEXIST) return READ_RECEIPT_FS_EXISTS;
    if (error == ENOENT) return READ_RECEIPT_FS_NOT_FOUND;
    if (error == EXDEV) return READ_RECEIPT_FS_IDENTITY_MISMATCH;
    if (error == ENOSYS || error == EINVAL || error == EOPNOTSUPP || error == EPERM) {
        return READ_RECEIPT_FS_UNSUPPORTED;
    }
    return READ_RECEIPT_FS_IO_ERROR;
}

static_assert(publish_errno_result(EEXIST) == READ_RECEIPT_FS_EXISTS);
static_assert(publish_errno_result(ENOENT) == READ_RECEIPT_FS_NOT_FOUND);
static_assert(publish_errno_result(EXDEV) == READ_RECEIPT_FS_IDENTITY_MISMATCH);
static_assert(publish_errno_result(ENOSYS) == READ_RECEIPT_FS_UNSUPPORTED);
static_assert(publish_errno_result(EINVAL) == READ_RECEIPT_FS_UNSUPPORTED);
static_assert(publish_errno_result(EOPNOTSUPP) == READ_RECEIPT_FS_UNSUPPORTED);
static_assert(publish_errno_result(EPERM) == READ_RECEIPT_FS_UNSUPPORTED);
static_assert(publish_errno_result(EIO) == READ_RECEIPT_FS_IO_ERROR);

int rename_noreplace(
        const char* temp_path, int directory_fd, const char* basename,
        jlong expected_device, jlong expected_inode) {
#if defined(SYS_renameat2)
    struct stat source_status{};
    if (fstatat(AT_FDCWD, temp_path, &source_status, AT_SYMLINK_NOFOLLOW) != 0) {
        return errno_result(errno);
    }
    if (!exact_single_link_regular(source_status, expected_device, expected_inode)) {
        return READ_RECEIPT_FS_IDENTITY_MISMATCH;
    }
    if (syscall(SYS_renameat2, AT_FDCWD, temp_path, directory_fd, basename,
                RENAME_NOREPLACE) != 0) {
        return publish_errno_result(errno);
    }
    ScopedFd live_fd(openat(
            directory_fd, basename, O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (live_fd.get() < 0) return errno_result(errno);
    return verify_single_link_fd(live_fd.get(), expected_device, expected_inode);
#else
    (void) temp_path;
    (void) directory_fd;
    (void) basename;
    (void) expected_device;
    (void) expected_inode;
    return READ_RECEIPT_FS_UNSUPPORTED;
#endif
}

int publish(const char* temp_path, const char* live_directory, const char* basename,
            jlong expected_device, jlong expected_inode) {
    if (!valid_path(temp_path)
            || !valid_path(live_directory)
            || !valid_basename(basename)
            || !valid_identity(expected_device, expected_inode)) {
        return READ_RECEIPT_FS_INVALID_ARGUMENT;
    }

    ScopedFd temp_fd(open(temp_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (temp_fd.get() < 0) return errno_result(errno);
    const int temp_verification = verify_single_link_fd(
            temp_fd.get(), expected_device, expected_inode);
    if (temp_verification != READ_RECEIPT_FS_OK) return temp_verification;

    ScopedFd directory_fd(open(
            live_directory, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
    if (directory_fd.get() < 0) return errno_result(errno);
    struct stat directory_status{};
    if (fstat(directory_fd.get(), &directory_status) != 0) return errno_result(errno);
    if (!S_ISDIR(directory_status.st_mode)
            || static_cast<std::uint64_t>(directory_status.st_dev)
                    != static_cast<std::uint64_t>(expected_device)) {
        return READ_RECEIPT_FS_IDENTITY_MISMATCH;
    }

    return rename_noreplace(
            temp_path, directory_fd.get(), basename, expected_device, expected_inode);
}

int fsync_path(const char* path, jlong expected_device, jlong expected_inode) {
    if (!valid_path(path) || !valid_identity(expected_device, expected_inode)) {
        return READ_RECEIPT_FS_INVALID_ARGUMENT;
    }
    ScopedFd descriptor(open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (descriptor.get() < 0) return errno_result(errno);
    const int verification = verify_fd(descriptor.get(), expected_device, expected_inode);
    if (verification != READ_RECEIPT_FS_OK) return verification;
    return fsync(descriptor.get()) == 0 ? READ_RECEIPT_FS_OK : errno_result(errno);
}

int fsync_directory(const char* directory) {
    if (!valid_path(directory)) return READ_RECEIPT_FS_INVALID_ARGUMENT;
    ScopedFd descriptor(open(
            directory, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
    if (descriptor.get() < 0) return errno_result(errno);
    return fsync(descriptor.get()) == 0 ? READ_RECEIPT_FS_OK : errno_result(errno);
}

int unlink_exact(const char* directory, const char* basename,
                 jlong expected_device, jlong expected_inode) {
    if (!valid_path(directory)
            || !valid_temp_basename(basename)
            || !valid_identity(expected_device, expected_inode)) {
        return READ_RECEIPT_FS_INVALID_ARGUMENT;
    }
    ScopedFd directory_fd(open(
            directory, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
    if (directory_fd.get() < 0) return errno_result(errno);
    struct stat directory_status{};
    if (fstat(directory_fd.get(), &directory_status) != 0) return errno_result(errno);
    if (!S_ISDIR(directory_status.st_mode)
            || static_cast<std::uint64_t>(directory_status.st_dev)
                    != static_cast<std::uint64_t>(expected_device)) {
        return READ_RECEIPT_FS_IDENTITY_MISMATCH;
    }

    ScopedFd candidate_fd(openat(
            directory_fd.get(), basename, O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (candidate_fd.get() < 0) return errno_result(errno);
    const int descriptor_verification = verify_single_link_fd(
            candidate_fd.get(), expected_device, expected_inode);
    if (descriptor_verification != READ_RECEIPT_FS_OK) return descriptor_verification;
    struct stat path_status{};
    if (fstatat(directory_fd.get(), basename, &path_status, AT_SYMLINK_NOFOLLOW) != 0) {
        return errno_result(errno);
    }
    if (!exact_single_link_regular(path_status, expected_device, expected_inode)) {
        return READ_RECEIPT_FS_IDENTITY_MISMATCH;
    }
    if (unlinkat(directory_fd.get(), basename, 0) != 0) return errno_result(errno);

    struct stat unlinked_status{};
    if (fstat(candidate_fd.get(), &unlinked_status) != 0) return errno_result(errno);
    return exact_regular(unlinked_status, expected_device, expected_inode)
            && unlinked_status.st_nlink == 0
            ? READ_RECEIPT_FS_OK
            : READ_RECEIPT_FS_IO_ERROR;
}

}

extern "C" JNIEXPORT jint JNICALL
Java_app_revanced_extension_kakaotalk_chatlog_readreceipt_ReadReceiptNativeFs_nativePublish(
        JNIEnv* environment, jclass, jstring temp_path, jstring live_directory,
        jstring basename, jlong expected_device, jlong expected_inode) {
    ScopedUtfChars temp(environment, temp_path);
    ScopedUtfChars directory(environment, live_directory);
    ScopedUtfChars name(environment, basename);
    if (environment->ExceptionCheck()) {
        environment->ExceptionClear();
        return READ_RECEIPT_FS_IO_ERROR;
    }
    return publish(
            temp.get(), directory.get(), name.get(), expected_device, expected_inode);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_revanced_extension_kakaotalk_chatlog_readreceipt_ReadReceiptNativeFs_nativeFsyncPath(
        JNIEnv* environment, jclass, jstring path, jlong expected_device,
        jlong expected_inode) {
    ScopedUtfChars value(environment, path);
    if (environment->ExceptionCheck()) {
        environment->ExceptionClear();
        return READ_RECEIPT_FS_IO_ERROR;
    }
    return fsync_path(value.get(), expected_device, expected_inode);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_revanced_extension_kakaotalk_chatlog_readreceipt_ReadReceiptNativeFs_nativeFsyncDirectory(
        JNIEnv* environment, jclass, jstring directory) {
    ScopedUtfChars value(environment, directory);
    if (environment->ExceptionCheck()) {
        environment->ExceptionClear();
        return READ_RECEIPT_FS_IO_ERROR;
    }
    return fsync_directory(value.get());
}

extern "C" JNIEXPORT jint JNICALL
Java_app_revanced_extension_kakaotalk_chatlog_readreceipt_ReadReceiptNativeFs_nativeUnlinkIfExactInode(
        JNIEnv* environment, jclass, jstring directory, jstring basename,
        jlong expected_device, jlong expected_inode) {
    ScopedUtfChars directory_value(environment, directory);
    ScopedUtfChars name(environment, basename);
    if (environment->ExceptionCheck()) {
        environment->ExceptionClear();
        return READ_RECEIPT_FS_IO_ERROR;
    }
    return unlink_exact(
            directory_value.get(), name.get(), expected_device, expected_inode);
}
