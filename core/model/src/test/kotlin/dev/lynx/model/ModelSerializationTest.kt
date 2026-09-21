package dev.lynx.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelSerializationTest {
    @Test
    fun databaseValueUsesTheStableMachineReadableShape() {
        val json = Json { encodeDefaults = true }

        val encoded = json.encodeToString<DatabaseValue>(DatabaseValue.BlobValue("AAE="))
        assertEquals("{\"kind\":\"blob\",\"data\":\"AAE=\",\"encoding\":\"base64\"}", encoded)
    }
}
