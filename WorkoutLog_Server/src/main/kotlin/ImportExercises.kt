package org.example

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * One-time script: reads exercises_seed.csv (src/main/resources) and inserts
 * each row into the Exercises table. Safe to re-run - rows that already
 * exist (same name) are skipped instead of crashing the whole import.
 */
fun main() {
    val url = "jdbc:postgresql://localhost:5432/WorkoutLog"
    val username = "postgres"
    val password = "postgres"

    Database.connect(
        url = url,
        driver = "org.postgresql.Driver",
        user = username,
        password = password
    )

    val inputStream = Thread.currentThread().contextClassLoader.getResourceAsStream("exercises_seed.csv")
        ?: error("Could not find exercises_seed.csv - make sure it's in src/main/resources/")

    val lines = BufferedReader(InputStreamReader(inputStream)).readLines()
    val dataLines = lines.drop(1) // first line is the header: name,muscle_group,equipment,category

    var inserted = 0
    var skipped = 0

    transaction {
        for (line in dataLines) {
            if (line.isBlank()) continue

            val parts = line.split(",")
            val exerciseName = parts.getOrNull(0)?.trim().orEmpty()
            val muscleGroupValue = parts.getOrNull(1)?.trim().orEmpty()
            val equipmentValue = parts.getOrNull(2)?.trim().orEmpty()
            val categoryValue = parts.getOrNull(3)?.trim().orEmpty().ifEmpty { "Strength" }

            if (exerciseName.isEmpty()) continue

            try {
                Exercises.insert {
                    it[name] = exerciseName
                    it[muscleGroup] = muscleGroupValue.ifEmpty { null }
                    it[equipment] = equipmentValue.ifEmpty { null }
                    it[category] = categoryValue
                }
                inserted++
            } catch (e: Exception) {
                // Most likely a duplicate name (unique constraint) if this script
                // has already been run before - safe to skip and keep going.
                skipped++
            }
        }
    }

    println("Done. Inserted: $inserted, skipped (likely duplicates): $skipped")
}
