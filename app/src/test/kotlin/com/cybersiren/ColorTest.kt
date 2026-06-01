package com.cybersiren

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorTest {
    fun getUsernameColor(identifier: String): Color {

        val hash = identifier.hashCode().toUInt()

        val colors = listOf(
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFFFFFF00),
            Color(0xFFFF00FF),
            Color(0xFF0080FF),
            Color(0xFFFF8000),
            Color(0xFF80FF00),
            Color(0xFF8000FF),
            Color(0xFFFF0080),
            Color(0xFF00FF80),
            Color(0xFF80FFFF),
            Color(0xFFFF8080),
            Color(0xFF8080FF),
            Color(0xFFFFFF80),
            Color(0xFFFF80FF),
            Color(0xFF80FF80),
        )

        return colors[(hash % colors.size.toUInt()).toInt()]
    }

    @Test
    fun is_username_derived_color_consistent() {

        println("Testing username color function:")

        val testUsers = listOf("alice", "bob", "charlie", "diana", "eve")

        testUsers.forEach { user ->
            val color = getUsernameColor(user)
            println("User '$user' gets color: ${color.value.toString(16).uppercase()}")
        }

        val `alice'sColor` =  getUsernameColor(testUsers[0])
        val `bob'sColor` = getUsernameColor(testUsers[1])
        val `charlie'sColor` = getUsernameColor(testUsers[2])
        val `diana'sColor` = getUsernameColor(testUsers[3])
        val `eve'sColor` = getUsernameColor(testUsers[4])

        println("\nTesting consistency:")
        repeat(3) {
            val `alice's_color` = getUsernameColor(testUsers[0])
            val `bob's_color` = getUsernameColor(testUsers[1])
            val `charlie's_color` = getUsernameColor(testUsers[2])
            val `diana's_color` = getUsernameColor(testUsers[3])
            val `eve's_color` = getUsernameColor(testUsers[4])

            assertEquals(`alice'sColor`, `alice's_color`)
            assertEquals(`bob'sColor`, `bob's_color`)
            assertEquals(`charlie'sColor`, `charlie's_color`)
            assertEquals(`diana'sColor`, `diana's_color`)
            assertEquals(`eve'sColor`, `eve's_color`)

            println("Alice color (test ${it + 1}): ${`alice'sColor`.value.toString(16).uppercase()}")
        }
    }
}
