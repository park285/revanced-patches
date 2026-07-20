package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ReadReceiptSchemaCatalog {

    static final long APPLICATION_ID = 0x49525232L;
    static final long USER_VERSION = 2L;

    private static final String DOMAIN = "read-receipt-revanced-schema-v2";

    private final List<SchemaObject> expectedObjects;
    private final byte[] expectedDigest;

    ReadReceiptSchemaCatalog(List<SchemaObject> expectedObjects) {
        this.expectedObjects = canonicalObjects(expectedObjects, true);
        this.expectedDigest = digest(APPLICATION_ID, USER_VERSION, this.expectedObjects);
    }

    Classification classify(long applicationId, long userVersion, List<SchemaObject> actualObjects) {
        if (applicationId != APPLICATION_ID || userVersion != USER_VERSION) {
            return Classification.SCHEMA_UNAVAILABLE;
        }
        try {
            List<SchemaObject> canonicalActual = canonicalObjects(actualObjects, true);
            byte[] actualDigest = digest(applicationId, userVersion, canonicalActual);
            if (!expectedObjects.equals(canonicalActual)
                    || !MessageDigest.isEqual(expectedDigest, actualDigest)) {
                return Classification.SCHEMA_UNAVAILABLE;
            }
            return Classification.SUPPORTED;
        } catch (RuntimeException ignored) {
            return Classification.SCHEMA_UNAVAILABLE;
        }
    }

    static byte[] encode(long applicationId, long userVersion, List<SchemaObject> objects) {
        List<SchemaObject> canonical = canonicalObjects(objects, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeString(output, DOMAIN);
        writeLong(output, applicationId);
        writeLong(output, userVersion);
        writeUnsignedInt(output, canonical.size());
        for (SchemaObject object : canonical) {
            writeString(output, object.type);
            writeString(output, object.name);
            writeString(output, object.tableName);
            if (object.sql == null) {
                output.write(0);
            } else {
                output.write(1);
                writeString(output, object.sql);
            }
        }
        return output.toByteArray();
    }

    static byte[] digest(long applicationId, long userVersion, List<SchemaObject> objects) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(encode(applicationId, userVersion, objects));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static List<SchemaObject> canonicalObjects(List<SchemaObject> objects, boolean rejectDuplicates) {
        if (objects == null) {
            throw invalidCatalog();
        }
        List<EncodedObject> encoded = new ArrayList<>(objects.size());
        for (SchemaObject object : objects) {
            if (object == null) {
                throw invalidCatalog();
            }
            encoded.add(new EncodedObject(object));
        }
        Collections.sort(encoded, EncodedObject.ORDER);

        List<SchemaObject> canonical = new ArrayList<>(encoded.size());
        Set<ObjectKey> keys = rejectDuplicates ? new HashSet<>() : null;
        for (EncodedObject object : encoded) {
            if (keys != null && !keys.add(new ObjectKey(object.value))) {
                throw invalidCatalog();
            }
            canonical.add(object.value);
        }
        return Collections.unmodifiableList(canonical);
    }

    private static void writeLong(ByteArrayOutputStream output, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            output.write((int) (value >>> shift) & 0xff);
        }
    }

    private static void writeUnsignedInt(ByteArrayOutputStream output, long value) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw invalidCatalog();
        }
        for (int shift = 24; shift >= 0; shift -= 8) {
            output.write((int) (value >>> shift) & 0xff);
        }
    }

    private static void writeString(ByteArrayOutputStream output, String value) {
        byte[] bytes = utf8(value);
        writeUnsignedInt(output, bytes.length);
        output.write(bytes, 0, bytes.length);
    }

    private static byte[] utf8(String value) {
        if (value == null) {
            throw invalidCatalog();
        }
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw invalidCatalog();
        }
    }

    private static IllegalArgumentException invalidCatalog() {
        return new IllegalArgumentException("invalid schema catalog");
    }

    enum Classification {
        SUPPORTED,
        SCHEMA_UNAVAILABLE
    }

    static final class SchemaObject {
        final String type;
        final String name;
        final String tableName;
        final String sql;

        SchemaObject(String type, String name, String tableName, String sql) {
            if (type == null || name == null || tableName == null) {
                throw invalidCatalog();
            }
            utf8(type);
            utf8(name);
            utf8(tableName);
            if (sql != null) {
                utf8(sql);
            }
            this.type = type;
            this.name = name;
            this.tableName = tableName;
            this.sql = sql;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SchemaObject)) return false;
            SchemaObject that = (SchemaObject) other;
            return type.equals(that.type)
                    && name.equals(that.name)
                    && tableName.equals(that.tableName)
                    && equalNullable(sql, that.sql);
        }

        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + name.hashCode();
            result = 31 * result + tableName.hashCode();
            result = 31 * result + (sql == null ? 0 : sql.hashCode());
            return result;
        }
    }

    private static final class EncodedObject {
        private static final Comparator<EncodedObject> ORDER = (left, right) -> {
            int type = compareUnsigned(left.type, right.type);
            if (type != 0) return type;
            int name = compareUnsigned(left.name, right.name);
            if (name != 0) return name;
            return compareUnsigned(left.tableName, right.tableName);
        };

        private final SchemaObject value;
        private final byte[] type;
        private final byte[] name;
        private final byte[] tableName;

        private EncodedObject(SchemaObject value) {
            this.value = value;
            this.type = utf8(value.type);
            this.name = utf8(value.name);
            this.tableName = utf8(value.tableName);
        }
    }

    private static final class ObjectKey {
        private final String type;
        private final String name;
        private final String tableName;

        private ObjectKey(SchemaObject object) {
            this.type = object.type;
            this.name = object.name;
            this.tableName = object.tableName;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ObjectKey)) return false;
            ObjectKey that = (ObjectKey) other;
            return type.equals(that.type) && name.equals(that.name) && tableName.equals(that.tableName);
        }

        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + name.hashCode();
            return 31 * result + tableName.hashCode();
        }
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int comparison = Integer.compare(left[index] & 0xff, right[index] & 0xff);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }

    private static boolean equalNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private ReadReceiptSchemaCatalog() {
        throw new AssertionError();
    }
}
