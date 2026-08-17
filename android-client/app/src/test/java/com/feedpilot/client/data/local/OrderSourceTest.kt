package com.feedpilot.client.data.local

import com.feedpilot.client.common.extractInstagramHandle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the order-source flag and the handle extraction used to label external orders.
 * Both are pure logic, so no database or network is involved.
 */
class OrderSourceTest {

    @Test
    fun `from maps stored names back to the enum`() {
        assertEquals(OrderSource.APP, OrderSource.from("APP"))
        assertEquals(OrderSource.EXTERNAL, OrderSource.from("EXTERNAL"))
        assertEquals(OrderSource.EXTERNAL, OrderSource.from("external"))
    }

    /** Rows written before the column existed, and any junk, must read as app orders. */
    @Test
    fun `from defaults to APP for unknown or missing values`() {
        assertEquals(OrderSource.APP, OrderSource.from(null))
        assertEquals(OrderSource.APP, OrderSource.from(""))
        assertEquals(OrderSource.APP, OrderSource.from("SOMETHING_ELSE"))
    }

    @Test
    fun `entity defaults to an app order and exposes it as the enum`() {
        val log = OrderHistoryEntity(
            providerNickname = "Hanuman SMM Panel",
            providerUrl = "https://hanumansmm.in/api/v2",
            targetUsername = "yrashi038",
            orderType = "Followers",
            quantity = 100,
            coinsSpent = 800,
            status = "In Progress"
        )

        assertEquals(OrderSource.APP.name, log.source)
        assertEquals(OrderSource.APP, log.orderSource)
    }

    // ------------------------------------------------------------- handle extraction

    private fun handleOf(link: String): String = extractInstagramHandle(link)

    /** Live admin-queue links carry an `igsh` tracking parameter. */
    @Test
    fun `strips tracking query strings from profile links`() {
        assertEquals(
            "pmshrisages_ems_lawan",
            handleOf("https://www.instagram.com/pmshrisages_ems_lawan?igsh=M29hbGhrcWZieHQ5")
        )
    }

    @Test
    fun `reads a plain profile link`() {
        assertEquals("yrashi038", handleOf("https://www.instagram.com/yrashi038"))
        assertEquals("yrashi038", handleOf("https://www.instagram.com/yrashi038/"))
    }

    @Test
    fun `labels post and reel links by their code`() {
        assertEquals("C_abc123", handleOf("https://www.instagram.com/p/C_abc123/"))
        assertEquals("DXyz", handleOf("https://www.instagram.com/reel/DXyz/"))
    }

    @Test
    fun `falls back to the raw value for unrecognised links`() {
        assertEquals("not-a-url", handleOf("not-a-url"))
    }
}
