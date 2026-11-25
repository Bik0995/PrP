package org.prp.android

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallationTest {
    private val dumpsys = """
User UserInfo{0:Owner:c13}:
  Accounts: 3
    Account {name=stuart@gmail.com, type=com.google}
    Account {name=+79991234567, type=org.telegram.messenger}
    Account {name=Stuart, type=com.whatsapp.w4b}

  AccountId, Action_Type, timestamp, UID, TableName, Key
    Account {name=stuart@gmail.com, type=com.google}
""".trimIndent()

    @Test fun comptesLus() {
        assertEquals(listOf("com.google", "org.telegram.messenger", "com.whatsapp.w4b"), Installation.typesComptes(dumpsys))
    }

    @Test fun aucunCompte() {
        assertEquals(emptyList<String>(), Installation.typesComptes("User UserInfo{0:Owner:c13}:\n  Accounts: 0\n"))
    }

    @Test fun utilisateurs() {
        assertEquals(1, Installation.nbUtilisateurs("Users:\n\tUserInfo{0:Owner:c13} running\n"))
        assertEquals(2, Installation.nbUtilisateurs("Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:Travail:1030} running\n"))
    }
}
