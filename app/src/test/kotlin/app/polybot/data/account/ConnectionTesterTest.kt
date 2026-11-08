package app.polybot.data.account

import app.polybot.core.result.ApiError
import app.polybot.data.api.ApiGateway
import app.polybot.data.api.dto.ApiJson
import app.polybot.data.api.dto.AssetBalanceDto
import app.polybot.data.api.dto.BalancesResponse
import app.polybot.data.api.dto.MeResponse
import app.polybot.data.api.dto.OpenOrdersResponse
import app.polybot.data.api.dto.SpotConfigResponse
import app.polybot.data.api.services.AuthApi
import app.polybot.data.api.services.LedgerApi
import app.polybot.data.api.services.OrdersApi
import app.polybot.data.catalog.CatalogRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionTesterTest {
    private val gateway = mockk<ApiGateway>(relaxed = true)
    private val auth = mockk<AuthApi>()
    private val ledger = mockk<LedgerApi>()
    private val orders = mockk<OrdersApi>()
    private val catalog = mockk<CatalogRepository>()
    private val tester = ConnectionTester(gateway, auth, ledger, orders, catalog)

    private val spot = CatalogRepository.toCatalog(
        ApiJson.decodeFromString(
            SpotConfigResponse.serializer(),
            javaClass.getResource("/spot_config_testnet.json")!!.readText(),
        ),
    )

    private fun happyPath() {
        coEvery { catalog.hydrate() } returns spot
        coEvery { auth.me() } returns MeResponse(accountId = "RLxqJGUDg92", apiKeyId = "ak_test")
        coEvery { ledger.balances() } returns BalancesResponse(listOf(AssetBalanceDto(assetId = 1), AssetBalanceDto(assetId = 2)))
        coEvery { orders.openOrders(any(), any()) } returns OpenOrdersResponse()
    }

    @Test
    fun `all steps pass`() = runBlocking {
        happyPath()

        val report = tester.run()

        assertTrue(report.allOk)
        assertEquals(4, report.steps.size)
        assertEquals("RLxqJGUDg92", report.accountId)
        assertEquals(2, report.balanceCount)
        assertEquals(0, report.openOrderCount)
    }

    @Test
    fun `a permission error on balances is reported per step and the other steps still run`() = runBlocking {
        happyPath()
        coEvery { ledger.balances() } throws ApiError.Auth("permission_denied", "ledger read not granted")

        val report = tester.run()

        assertFalse(report.allOk)
        val failed = report.steps.filterNot { it.ok }
        assertEquals(listOf("Baca saldo (GetBalances)"), failed.map { it.name })
        assertTrue(failed.single().detail.contains("Izin ditolak"))
        assertTrue(failed.single().detail.contains("ledger read not granted"), "pesan server ikut ditampilkan")
        assertEquals("RLxqJGUDg92", report.accountId, "Me tetap berhasil")
        assertEquals(0, report.openOrderCount, "order terbuka tetap dicek")
        assertNull(report.balanceCount)
    }

    @Test
    fun `a rejected signature fails every authenticated step but not the public one`() = runBlocking {
        coEvery { catalog.hydrate() } returns spot
        val rejected = ApiError.Auth("SIGNATURE_INVALID", "bad")
        coEvery { auth.me() } throws rejected
        coEvery { ledger.balances() } throws rejected
        coEvery { orders.openOrders(any(), any()) } throws rejected

        val report = tester.run()

        assertEquals(listOf(true, false, false, false), report.steps.map { it.ok })
        assertNull(report.accountId)
        assertTrue(report.steps[1].detail.contains("private key tidak cocok"))
    }

    @Test
    fun `catalog failure is thrown because nothing else is meaningful without it`() {
        coEvery { catalog.hydrate() } throws ApiError.Network(java.io.IOException("offline"))
        assertThrows(ApiError.Network::class.java) { runBlocking { tester.run() } }
    }
}
