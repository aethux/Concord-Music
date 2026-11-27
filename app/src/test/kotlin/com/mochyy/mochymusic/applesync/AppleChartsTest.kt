// Concord: new — Apple's charts answer, as the page reads it.
//
// The shapes here are trimmed from the live responses probed 2026-11-27 (storefront `in`): every
// type in `results` is a **list of chart groups**, `meta.results.order` names them in Apple's order,
// and a row's own `type` decides whether it is a song row or a card.
package com.mochyy.mochymusic.applesync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleChartsTest {

    private fun page(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `the genre index keeps Apple's order, root first`() {
        val genres = AppleChartsReader.genres(
            page(
                """
                {"data":[
                  {"id":"34","type":"genres","attributes":{"name":"Music"}},
                  {"id":"14","type":"genres","attributes":{"name":"Pop","parentId":"34"}}
                ]}
                """
            )
        )
        assertEquals(listOf("34", "14"), genres.map { it.id })
        assertEquals(listOf("Music", "Pop"), genres.map { it.name })
    }

    @Test
    fun `the charts follow meta results order, not the results keys`() {
        val charts = AppleChartsReader.charts("34", page(liveRoot))!!
        assertEquals(
            listOf("most-played:songs", "city-top:cityCharts", "most-played:albums"),
            charts.groups.map { it.id },
        )
        assertEquals(listOf("Top Songs", "City Charts", "Top Albums"), charts.groups.map { it.title })
    }

    @Test
    fun `a song chart is song rows, a playlist chart is playlist cards`() {
        val charts = AppleChartsReader.charts("34", page(liveRoot))!!
        val songs = charts.groups.first { it.id == "most-played:songs" }
        assertTrue(songs.cards.isEmpty())
        assertEquals(1, songs.songs.size)
        assertEquals("Ashke", songs.songs.first().title)
        assertEquals(217_625L, songs.songs.first().durationMs)

        val city = charts.groups.first { it.id == "city-top:cityCharts" }
        assertTrue(city.songs.isEmpty())
        assertEquals(1, city.cards.size)
        assertEquals(AppleRadioItem.Kind.Playlist, city.cards.first().kind)
        assertEquals("Top 25: Mumbai", city.cards.first().title)
    }

    @Test
    fun `a group the order names but Apple did not send is skipped`() {
        val charts = AppleChartsReader.charts("14", page(orderWithoutGroups))!!
        assertEquals(listOf("most-played:songs"), charts.groups.map { it.id })
    }

    @Test
    fun `without meta order every group still draws`() {
        val charts = AppleChartsReader.charts("34", page(noOrder))!!
        assertEquals(2, charts.groups.size)
        assertEquals(setOf("most-played:songs", "most-played:albums"), charts.groups.map { it.id }.toSet())
    }

    @Test
    fun `a page with no results is a page with no groups`() {
        assertEquals(emptyList<AppleChartGroup>(), AppleChartsReader.charts("34", page("""{"results":{}}"""))!!.groups)
    }

    // trimmed from /v1/catalog/in/charts?genre=34&with=cityCharts,dailyGlobalTopCharts
    private val liveRoot = """
        {
          "results": {
            "songs": [{"chart":"most-played","name":"Top Songs","orderId":"most-played:songs","data":[
              {"id":"6814002647","type":"songs","attributes":{
                "name":"Ashke","artistName":"Karan Aujla & MXRCI","durationInMillis":217625,
                "artwork":{"url":"https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/f7/68/x.jpg/{w}x{h}bb.{f}"},
                "playParams":{"id":"6814002647","kind":"song"}}}
            ]}],
            "cityCharts": [{"chart":"city-top","name":"City Charts","orderId":"city-top:cityCharts","data":[
              {"id":"pl.db537759ae3341eaa600bc5482209f7c","type":"playlists","attributes":{
                "name":"Top 25: Mumbai","curatorName":"Apple Music","isChart":true,
                "playParams":{"id":"pl.db537759ae3341eaa600bc5482209f7c","kind":"playlist"}}}
            ]}],
            "albums": [{"chart":"most-played","name":"Top Albums","orderId":"most-played:albums","data":[
              {"id":"6837567866","type":"albums","attributes":{
                "name":"She's the Best","artistName":"Troye Sivan",
                "playParams":{"id":"6837567866","kind":"album"}}}
            ]}]
          },
          "meta": {"results": {"order": [
            "most-played:songs","city-top:cityCharts","most-played:albums"
          ]}}
        }
    """.trimIndent()

    private val orderWithoutGroups = """
        {
          "results": {
            "songs": [{"name":"Top Songs","orderId":"most-played:songs","data":[
              {"id":"1","type":"songs","attributes":{"name":"A","artistName":"B"}}
            ]}]
          },
          "meta": {"results": {"order": [
            "most-played:songs","most-played:playlists","most-played:albums"
          ]}}
        }
    """.trimIndent()

    private val noOrder = """
        {
          "results": {
            "songs": [{"name":"Top Songs","orderId":"most-played:songs","data":[
              {"id":"1","type":"songs","attributes":{"name":"A","artistName":"B"}}
            ]}],
            "albums": [{"name":"Top Albums","orderId":"most-played:albums","data":[
              {"id":"2","type":"albums","attributes":{"name":"C","artistName":"D"}}
            ]}]
          }
        }
    """.trimIndent()
}
