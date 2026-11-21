package akhil.alltrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MtopTranslatorScanTest {

    private val json = """{"api":"mtop.trade.query.bag","data":{"price":1.10,"count":3,""" +
        """"item":{"title":"相机背带","itemId":"77123","skuId":"店铺","url":"https://a.b/店"},""" +
        """"tags":["退货宝","极速退款"],"submit":"{\"title\":\"结算\"}","ret":["SUCCESS::调用成功"]}}"""

    @Test
    fun collectsOnlyDisplayKeys() {
        val found = LinkedHashSet<String>()
        MtopTranslator.scan(json, null, found)
        assertEquals(setOf("相机背带", "退货宝", "极速退款"), found)
    }

    @Test
    fun replacesInPlaceAndKeepsEverythingElse() {
        val map = mapOf("相机背带" to "Dây đeo máy ảnh", "退货宝" to "Bảo hiểm trả hàng")
        val out = MtopTranslator.scan(json, map, null)!!
        val expected = json
            .replace("\"相机背带\"", "\"Dây đeo máy ảnh\"")
            .replace("\"退货宝\"", "\"Bảo hiểm trả hàng\"")
        // Numbers (1.10), ids, urls, embedded JSON and ret messages are byte-identical.
        assertEquals(expected, out)
    }

    @Test
    fun rejectsBrokenJson() {
        assertNull(MtopTranslator.scan("""{"title":"相机""", null, LinkedHashSet()))
    }
}
