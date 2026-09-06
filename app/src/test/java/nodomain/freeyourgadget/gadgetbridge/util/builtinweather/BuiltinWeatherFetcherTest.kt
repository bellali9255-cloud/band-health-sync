package nodomain.freeyourgadget.gadgetbridge.util.builtinweather

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinWeatherFetcherTest : TestBase() {
    @Test
    fun parsesOpenMeteoCurrentAndForecast() {
        val json = JSONObject(
            """
            {
              "timezone":"Asia/Singapore",
              "current":{"time":"2026-09-06T12:00","temperature_2m":30.0,"relative_humidity_2m":70,"apparent_temperature":34.0,"weather_code":1,"wind_speed_10m":12.0,"wind_direction_10m":90},
              "daily":{"weather_code":[1,61,3],"temperature_2m_max":[32,31,30],"temperature_2m_min":[26,25,24],"precipitation_probability_max":[20,60,10],"sunrise":["2026-09-06T06:58","2026-09-07T06:58","2026-09-08T06:58"],"sunset":["2026-09-06T19:06","2026-09-07T19:06","2026-09-08T19:06"]},
              "hourly":{"time":["2026-09-06T12:00"],"temperature_2m":[30.0],"relative_humidity_2m":[70],"precipitation_probability":[20],"weather_code":[1],"wind_speed_10m":[12.0],"wind_direction_10m":[90]}
            }
            """.trimIndent()
        )

        val spec = BuiltinWeatherFetcher.parse(json, "当前位置", 1.3f, 103.8f, getContext())

        assertEquals("当前位置", spec.location)
        assertEquals(303, spec.currentTemp)
        assertEquals(801, spec.currentConditionCode)
        assertEquals(70, spec.currentHumidity)
        assertEquals(2, spec.forecasts.size)
        assertEquals(500, spec.forecasts[0].conditionCode)
        assertEquals(1, spec.hourly.size)
        assertTrue(spec.sunRise > 0)
        assertTrue(spec.sunSet > spec.sunRise)
    }
}
