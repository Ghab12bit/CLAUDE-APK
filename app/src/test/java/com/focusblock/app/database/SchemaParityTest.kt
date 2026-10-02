package com.focusblock.app.database

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 16 → 17 migration is written by hand. This test compares it with the schema Room exports for
 * version 17, so a mismatch fails the build instead of crashing on a phone at first launch.
 */
class SchemaParityTest {
    private val dir = File("schemas/com.focusblock.app.database.FocusBlockDatabase")

    private fun schema(version: Int): JSONObject {
        val file = File(dir, "$version.json")
        assertTrue("Room schema $file was not exported; build the app first", file.exists())
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun entities(version: Int): Map<String, JSONObject> {
        val list = schema(version).getJSONArray("entities")
        return (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("tableName") }
    }

    @Test fun createdTablesMatchRoomsSchema() {
        val v17 = entities(17)
        for (statement in Migration16To17.CREATE_STATEMENTS) {
            val table = Regex("CREATE TABLE IF NOT EXISTS `([^`]+)`").find(statement)!!.groupValues[1]
            val entity = v17[table]
            assertNotNull("Table $table is missing from the v17 schema", entity)
            val expected = entity!!.getString("createSql").replace("\${TABLE_NAME}", table)
            assertEquals("CREATE for $table", expected, statement)
            val indices = entity.optJSONArray("indices")
            assertTrue("$table declares indices the migration does not create", indices == null || indices.length() == 0)
        }
    }

    @Test fun addedColumnsMatchRoomsSchema() {
        val v16 = entities(16)
        val v17 = entities(17)
        val pattern = Regex("ALTER TABLE `([^`]+)` ADD COLUMN `([^`]+)` (\\w+)( NOT NULL)?( DEFAULT (.+))?")
        for (statement in Migration16To17.ALTER_STATEMENTS) {
            val m = pattern.find(statement)!!
            val (table, column, affinity) = m.destructured
            val notNull = m.groupValues[4].isNotEmpty()
            val default = m.groupValues[6].ifEmpty { null }
            val fields = v17[table]!!.getJSONArray("fields")
            val field = (0 until fields.length()).map { fields.getJSONObject(it) }.firstOrNull { it.getString("columnName") == column }
            assertNotNull("$table.$column missing from the v17 schema", field)
            assertEquals("$table.$column affinity", field!!.getString("affinity"), affinity)
            assertEquals("$table.$column NOT NULL", field.getBoolean("notNull"), notNull)
            assertEquals("$table.$column default", field.optString("defaultValue").ifEmpty { null }, default)
            val old = v16[table]!!.getJSONArray("fields")
            assertTrue("$table.$column already existed in v16", (0 until old.length()).none { old.getJSONObject(it).getString("columnName") == column })
        }
    }

    @Test fun everyV16TableSurvivesUnchanged() {
        val v16 = entities(16)
        val v17 = entities(17)
        val altered = Migration16To17.ALTER_STATEMENTS.map { Regex("`([^`]+)`").find(it)!!.groupValues[1] }.toSet()
        for ((table, entity) in v16) {
            assertTrue("v16 table $table was dropped", table in v17)
            if (table !in altered) assertEquals("v16 table $table changed", entity.getString("createSql"), v17[table]!!.getString("createSql"))
        }
        val created = Migration16To17.CREATE_STATEMENTS.map { Regex("`([^`]+)`").find(it)!!.groupValues[1] }.toSet()
        assertEquals("Every new v17 table must be created by the migration", v17.keys - v16.keys, created)
    }
}
