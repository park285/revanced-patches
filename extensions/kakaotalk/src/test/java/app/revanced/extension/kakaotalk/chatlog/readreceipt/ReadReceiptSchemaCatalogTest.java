package app.revanced.extension.kakaotalk.chatlog.readreceipt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class ReadReceiptSchemaCatalogTest {

    private static final String SYNTHETIC_PREIMAGE_HEX =
            "0000001f726561642d726563656970742d726576616e6365642d736368656d612d7632"
                    + "000000004952523200000000000000020000000300000005696e646578000000116964"
                    + "785f72656365697074735f636861740000000872656365697074730100000033435245"
                    + "41544520494e444558206964785f72656365697074735f63686174204f4e2072656365"
                    + "6970747328636861745f69642900000005696e6465780000001b73716c6974655f6175"
                    + "746f696e6465785f72656365697074735f310000000872656365697074730000000005"
                    + "7461626c65000000087265636569707473000000087265636569707473010000004143"
                    + "5245415445205441424c452072656365697074732028696420494e5445474552205052"
                    + "494d415259204b45592c20746f6b656e205445585420554e4951554529";
    private static final String SYNTHETIC_DIGEST_HEX =
            "e76f7dcc9d41f7ac5b4d4191bedb71f9bab03633ea74e51ad31b4bc45f16430f";
    private static final String UNSIGNED_UTF8_ORDERING_PREIMAGE_HEX =
            "0000001f726561642d726563656970742d726576616e6365642d736368656d612d7632"
                    + "0000000049525232000000000000000200000005000000017400000001610000000178"
                    + "00000000017400000001610000000478e99baa0100000003e99baa0000000174000000"
                    + "02616100000003e8a1a8000000000174000000017f000000017a000000000174000000"
                    + "02c3a900000002c3a900";

    private static final List<ReadReceiptSchemaCatalog.SchemaObject> SYNTHETIC_CATALOG = Arrays.asList(
            object("table", "receipts", "receipts",
                    "CREATE TABLE receipts (id INTEGER PRIMARY KEY, token TEXT UNIQUE)"),
            object("index", "sqlite_autoindex_receipts_1", "receipts", null),
            object("index", "idx_receipts_chat", "receipts",
                    "CREATE INDEX idx_receipts_chat ON receipts(chat_id)")
    );

    @Test
    public void canonicalEncodingIsExactAndIndependentOfInputOrder() {
        assertEquals(SYNTHETIC_PREIMAGE_HEX, hex(ReadReceiptSchemaCatalog.encode(
                ReadReceiptSchemaCatalog.APPLICATION_ID,
                ReadReceiptSchemaCatalog.USER_VERSION,
                SYNTHETIC_CATALOG
        )));
        assertEquals(SYNTHETIC_PREIMAGE_HEX, hex(ReadReceiptSchemaCatalog.encode(
                ReadReceiptSchemaCatalog.APPLICATION_ID,
                ReadReceiptSchemaCatalog.USER_VERSION,
                Arrays.asList(
                        SYNTHETIC_CATALOG.get(1),
                        SYNTHETIC_CATALOG.get(0),
                        SYNTHETIC_CATALOG.get(2)
                )
        )));
        assertEquals(SYNTHETIC_DIGEST_HEX, hex(ReadReceiptSchemaCatalog.digest(
                ReadReceiptSchemaCatalog.APPLICATION_ID,
                ReadReceiptSchemaCatalog.USER_VERSION,
                SYNTHETIC_CATALOG
        )));
    }

    @Test
    public void exactExpectedSnapshotIsSupported() {
        ReadReceiptSchemaCatalog catalog = new ReadReceiptSchemaCatalog(SYNTHETIC_CATALOG);

        assertEquals(ReadReceiptSchemaCatalog.Classification.SUPPORTED, catalog.classify(
                ReadReceiptSchemaCatalog.APPLICATION_ID,
                ReadReceiptSchemaCatalog.USER_VERSION,
                Arrays.asList(
                        SYNTHETIC_CATALOG.get(2),
                        SYNTHETIC_CATALOG.get(1),
                        SYNTHETIC_CATALOG.get(0)
                )
        ));
    }

    @Test
    public void encodeAndDigestRejectDuplicateKeysRegardlessOfInputOrder() {
        ReadReceiptSchemaCatalog.SchemaObject first =
                object("index", "idx_receipts_chat", "receipts", "first SQL");
        ReadReceiptSchemaCatalog.SchemaObject second =
                object("index", "idx_receipts_chat", "receipts", "different SQL");

        assertInvalid(() -> ReadReceiptSchemaCatalog.encode(
                ReadReceiptSchemaCatalog.APPLICATION_ID,
                ReadReceiptSchemaCatalog.USER_VERSION,
                Arrays.asList(first, second)
        ));
        assertInvalid(() -> ReadReceiptSchemaCatalog.encode(
                ReadReceiptSchemaCatalog.APPLICATION_ID,
                ReadReceiptSchemaCatalog.USER_VERSION,
                Arrays.asList(second, first)
        ));
        assertInvalid(() -> ReadReceiptSchemaCatalog.digest(
                ReadReceiptSchemaCatalog.APPLICATION_ID,
                ReadReceiptSchemaCatalog.USER_VERSION,
                Arrays.asList(first, second)
        ));
        assertInvalid(() -> ReadReceiptSchemaCatalog.digest(
                ReadReceiptSchemaCatalog.APPLICATION_ID,
                ReadReceiptSchemaCatalog.USER_VERSION,
                Arrays.asList(second, first)
        ));
    }

    @Test
    public void canonicalOrderingUsesUnsignedUtf8BytesAndPrefixRules() {
        List<ReadReceiptSchemaCatalog.SchemaObject> objects = Arrays.asList(
                object("t", "é", "é", null),
                object("t", "aa", "表", null),
                object("t", "a", "x雪", "雪"),
                object("t", "\u007f", "z", null),
                object("t", "a", "x", null)
        );

        assertEquals(UNSIGNED_UTF8_ORDERING_PREIMAGE_HEX, hex(ReadReceiptSchemaCatalog.encode(
                ReadReceiptSchemaCatalog.APPLICATION_ID,
                ReadReceiptSchemaCatalog.USER_VERSION,
                objects
        )));
    }

    @Test
    public void everyMetadataOrCatalogMismatchIsSchemaUnavailable() {
        ReadReceiptSchemaCatalog catalog = new ReadReceiptSchemaCatalog(SYNTHETIC_CATALOG);
        ReadReceiptSchemaCatalog.Classification unavailable =
                ReadReceiptSchemaCatalog.Classification.SCHEMA_UNAVAILABLE;

        assertEquals(unavailable, catalog.classify(0, 2, SYNTHETIC_CATALOG));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 1,
                SYNTHETIC_CATALOG));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 3,
                SYNTHETIC_CATALOG));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 2,
                SYNTHETIC_CATALOG.subList(0, 2)));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 2,
                withExtra(object("view", "receipt_view", "receipt_view",
                        "CREATE VIEW receipt_view AS SELECT * FROM receipts"))));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 2,
                withExtra(SYNTHETIC_CATALOG.get(0))));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 2,
                replacing(0, object("view", "receipts", "receipts", SYNTHETIC_CATALOG.get(0).sql))));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 2,
                replacing(0, object("table", "Receipts", "receipts", SYNTHETIC_CATALOG.get(0).sql))));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 2,
                replacing(0, object("table", "receipts", "receipt", SYNTHETIC_CATALOG.get(0).sql))));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 2,
                replacing(0, object("table", "receipts", "receipts",
                        SYNTHETIC_CATALOG.get(0).sql + " "))));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 2,
                replacing(0, object("table", "receipts", "receipts", null))));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 2,
                replacing(1, object("index", "sqlite_autoindex_receipts_1", "receipts", ""))));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 2, null));
        assertEquals(unavailable, catalog.classify(ReadReceiptSchemaCatalog.APPLICATION_ID, 2,
                Arrays.asList(SYNTHETIC_CATALOG.get(0), null)));
    }

    @Test
    public void expectedCatalogIsDefensivelyCopiedAndInvalidTextIsBounded() {
        List<ReadReceiptSchemaCatalog.SchemaObject> mutable = new ArrayList<>(SYNTHETIC_CATALOG);
        ReadReceiptSchemaCatalog catalog = new ReadReceiptSchemaCatalog(mutable);
        mutable.clear();

        assertEquals(ReadReceiptSchemaCatalog.Classification.SUPPORTED, catalog.classify(
                ReadReceiptSchemaCatalog.APPLICATION_ID,
                ReadReceiptSchemaCatalog.USER_VERSION,
                SYNTHETIC_CATALOG
        ));
        assertInvalid(() -> object(null, "name", "table", "private SQL"));
        assertInvalid(() -> object("table", null, "table", "private SQL"));
        assertInvalid(() -> object("table", "name", null, "private SQL"));
        assertInvalid(() -> object("table", "bad\ud800name", "table", "private SQL"));
        assertInvalid(() -> object("table", "name", "table", "bad\udfff SQL"));
    }

    private static ReadReceiptSchemaCatalog.SchemaObject object(
            String type,
            String name,
            String tableName,
            String sql
    ) {
        return new ReadReceiptSchemaCatalog.SchemaObject(type, name, tableName, sql);
    }

    private static List<ReadReceiptSchemaCatalog.SchemaObject> withExtra(
            ReadReceiptSchemaCatalog.SchemaObject extra
    ) {
        List<ReadReceiptSchemaCatalog.SchemaObject> result = new ArrayList<>(SYNTHETIC_CATALOG);
        result.add(extra);
        return result;
    }

    private static List<ReadReceiptSchemaCatalog.SchemaObject> replacing(
            int index,
            ReadReceiptSchemaCatalog.SchemaObject replacement
    ) {
        List<ReadReceiptSchemaCatalog.SchemaObject> result = new ArrayList<>(SYNTHETIC_CATALOG);
        result.set(index, replacement);
        return result;
    }

    private static void assertInvalid(Runnable action) {
        try {
            action.run();
            fail("expected invalid schema catalog");
        } catch (IllegalArgumentException exception) {
            assertEquals("invalid schema catalog", exception.getMessage());
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
