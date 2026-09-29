package org.example

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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

    transaction {
        // Looks at the Exercises/Workouts/SetEntries definitions in Tables.kt
        // and creates any of them that don't already exist in Postgres.
        SchemaUtils.create(Exercises, Workouts, SetEntries)
    }

    println("Tables created (or already existed) successfully!")
}
