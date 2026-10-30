package com.nxnson.vesnavi.navcore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceSearchTest {
    @Test
    fun `Photon features become results`() {
        val json = """
        {"type":"FeatureCollection","features":[
          {"geometry":{"type":"Point","coordinates":[106.6981,10.7725]},
           "properties":{"name":"Chợ Bến Thành","street":"Lê Lợi","district":"Quận 1","city":"Thành phố Hồ Chí Minh"}},
          {"geometry":{"type":"Point","coordinates":[106.7,10.77]},
           "properties":{"housenumber":"12","street":"Nguyễn Huệ","city":"Thành phố Hồ Chí Minh"}},
          {"geometry":{"type":"Point","coordinates":[106.7]},"properties":{"name":"broken"}},
          {"geometry":{"type":"Point","coordinates":[106.7,10.77]},"properties":{}}
        ]}
        """
        val results = PlaceSearch.parsePhoton(json.toByteArray())
        assertEquals(2, results.size)
        assertEquals(SearchResult("Chợ Bến Thành", "Lê Lợi, Quận 1, Thành phố Hồ Chí Minh", Coordinate(10.7725, 106.6981)), results[0])
        assertEquals("12 Nguyễn Huệ", results[1].name)
        assertEquals("Thành phố Hồ Chí Minh", results[1].subtitle)
    }

    @Test
    fun `search URL is encoded and biased to the rider`() {
        val url = PlaceSearch.photonURL(" bến thành ", Coordinate(10.77, 106.7))
        assertTrue(url.startsWith("https://photon.komoot.io/api/?q=b%E1%BA%BFn+th%C3%A0nh"))
        assertTrue(url.endsWith("&lat=10.77000&lon=106.70000"))
    }
}
