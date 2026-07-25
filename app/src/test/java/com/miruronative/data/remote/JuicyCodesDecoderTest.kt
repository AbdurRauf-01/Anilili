package com.miruronative.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JuicyCodesDecoderTest {
    @Test
    fun reassemblesConcatenatedBlobFromEmbedHtml() {
        val html = """
            <div class="juicycodes-player" id="jc-player"></div>
            <script type="application/javascript">
                _juicycodes("cGFydA==" + "==" + "fgh");
            </script>
        """.trimIndent()

        assertEquals("cGFydA====fgh", JuicyCodesDecoder.extractBlob(html))
    }

    @Test
    fun missingBlobReturnsNull() {
        assertEquals(null, JuicyCodesDecoder.extractBlob("<html><body>no player here</body></html>"))
    }

    @Test
    fun decodesRealCapturedBlob() {
        // Captured 2026-07-24 from argon.razorshell.space/embed/5GQAmPt6K445Vk7
        // ("Karna the Guardian S01E01 [Hindi]"), split into readable chunks.
        val blob = listOf(
        """PSskLSsrKyVfKypeXy0hISUrKystKyokKisqKl4rKyErKys9PSsrXz0tISErLT0kXi0hISsrJF89LSFeJSskKyQrKz0hKysqXyskYCQrK14kLSFeXi09LSQtIV5fLV4rJC1eLSstXi0kLV8lKy0hXistX15eLSFeJSsrPV4rKiteKyslXisrXyQrKyQqLSFeJC09LSEt""",
        """IV5fKyteKiskYCUrJGA9KyohJSsqPSstPS0hKy0hXi1eJSQrLSFeLV4lPSsrJSUrKl4lKytfJSsqJC0rKipeLV5gKisqXj0rKyUrKyQhKisqJCErKl4qKyo9JCsrXiErKyQkKyotXisqLS0tXmA9Kyo9XysqISorKyUtKysrKisrJCQrLSEtLV4lPSsrKiErKyQtKysh""",
        """JCsrJV4rJCUkKyotKyskYD0rLT1fKyRgKisrXiErJCUrKyorJSsrLSQtXmAlKyohJCsqKi0rK18lLSFeLS1fXi0tIV4qKyohKisrPSUrKiFfK2BfLSsrKy0rKiQqKyoqXi0hXj0tPS1eKy0kLS0hXl4rKyReKyoqJCsrJS0rKy1eKyotKisrJD0rKyoqLSFeKi1fXiot""",
        """IV5eKysqKisrPV4rKj0tKyslISsrLV4rKi09KyskPSsrKiEtIV4kKy1fKy1fXl8tIV49KyslISsqPSErKiEhKyskKysrKy0rJGAtKypeKisrJSorJGBeKys9PSsqJF4tIV49LT0tJS0hXiEtXisqLV5eXy09LV8tPSU9LSFeXi1fXj0tIV4tKyslKyskJV8rJGAlKyok""",
        """JCsqPSorJGAlKyslISsqXl8rJGArLSFeXi09LV4rJGAhKypePSskJV8rKyRfLV9eKi0hXiErJGAqKys9KiskYC0rKi09KyskPS0hXiEtPS0hLSFeJStgPV8rKyUhKypeISsqKiErKyVeLSEhKiskYD0rK14kKyskIS0hIT0rYCQtKyQlJCsrJSUrKl4qKysqKisrPSor""",
        """KyUkKyoqJC0hIT0rJV9eLV4tKi1eKyUrYCsqLV4tIS1eKy0tISEkKy0kJCtgISErKz0rKyoqPSsrKl4rKz1eKy1fXi0hXi0tX14rLSFeISsrKj0rKz0rKyo9KysqISErKi0hKyslKyskJC0rJGAqKys9XiskYCErKi0rKyskIS0hXl8tPS1fKyRgKisqXisrJCUrKysk""",
        """Ki1fXiotIV4kKyRgXisqXiUrKyUtKysrKisqJT0rKj0rLSFeKy09LSorLSQhKyRfJC0hXiQrKiUhKys9KysqKiErKyohLSFePS09LV8tIV4tKyRgPSsrXj0rJCUrKyorXysrLSorKioqKyslLSsrPSQrKi0tKyo9JS0hXl8tX14qLSFeKisrISErKz1eKyotKysrJD0t""",
        """IV49LT0tIS0hXl8rK14qKyRgXyskYC0rKiElKyo9JS09LT0rLSEkLV4lXystIS0tXiUhKyQrXysrISUrKy1fKypeXysrKysrKiFeKytfLSsrKy0rK18lKysrKyskKz0rKmA9KypePSsqXysrK14lLV5gPSsrX14rKl5eKyokJSsqJC0rJC1fKyQkJC1eYF8rKitfKyok""",
        """XysqKl8rKj0qKyRgXysrJCQrKl49Ky0hXy1eJS0rKz0tKyorJCsrJSsrK19eKyskJSsqPSUrLSEtLV4lXi09PV8rJSRfK2BfJCskKj0tPT0lK2AkJSslJV4rJCpfK2BgJCslXiorYCErKyolXystKj0rJCshKyVgXysqJT0rJWA9LV4qJSstJSQrJCUhK2BgXytgXior""",
        """YCFfKyolLS09PS0rYCQ9K2AhJS1eKysrYGAtK2AkPSsqXysrJCtfKyUlKyslJCQrLSohLV4rXystKj0rJV4tKy0lJCskJCstPT0lKyorKisqISotXiQlKyVgJSskKz0rYC0rLV4qJCslJSorJCFeKy1gPSsqLT0rJWAhLV4qKyslJSsrJCQrKy0qJSskKz0rKiElKyor""",
        """PS09PV8rYCQlKyUlKiskLS0rJSUkKyUkJCskYD0tXiRfKy0qJSskKz0rJSFfKypgJC09PSQrJV5eK2AhLSsqKz0tPT1fKyorISslLS0rKi0lKyVgJStgXl8rYC0rLV4tKislJT0rJSoqLV5fXitgPSErYCEkK2AkIStgLSUrKi1fKyokLS1eKl8rKl8tLV4rXytgXy0r""",
        """Ki0qLV4tJCsqYCQrLSstK2AhXislXyUrLWAqK2BeXytgPSErKiElKyotLSsqXyUrYCEtKy1gPSsqYCsrKioqKyUhJCsqXy0rJSs9Ky0rISskISorLSVfKyUkLSslJSQrJCElKyolKy1eXiorKiorKyUkJCskJF8rYCRfKyoqJStgPSErJCRfK2AhKysqXz0rJV4kKyUl""",
        """PS09PV4tPT0tLV4qXisrKz0rKz0kKypfXyslXiQrYCshK2BgXyslYF8rKitfKyVfKisqLSErKl4tK2BeLS1eKyorJSEkKy0rKytgXisrKmAhKyolJC09PSQrJT1eKyUrXysrPV4rYCtfK2BeKytgX14rJCohKy0qXitgXi0rYF8qKyQqLStgYF8rJSEqKysrKislPV4r""",
        """YC0kLV4kKitgKyQrKiUhKyUlLStgXz0rKytfK2AtJStgLSotXislKyUrKytgLSorYGAqK2AkJCslXystXislK2ArKitgISQtPT09KyUqKitgXyQtXis9LT0lKysrXyUrKiE9K2BeXytgLSQrKiU9Ky0rXitgXl4rJCo9Ky0hPS1eJSUrLSolKyQrLSsqXyorJCsrKyVg""",
        """KisqK14rJGAhLV4qPS09PSsrYF4kKyVgKy1eJCorJWAtKyQhJCslXyorJCsrLT09KytgJCErJCQrKyQqPStgYF8rYCRfK2BfXyskJSUtPT0hKyQrKysqIV8tXiFeLT09XysqKz0rYF8qKyQrPS09PT0rYCQrK2ArXyskKi0rJSVfK2AkPSsqXyUrJCVfKyVgJC1eKi0r""",
        """JWBfKyolLSslYCUrYF4qKyVfJSskKyEtPT09KyQrXyskYCUtXi0tLT09LSskIV8rYCFeLV5fPSslJSUrJCs9KyQqPS1eX14rJWBeKyQrJSskYCEtXiEhKy0qISsqK18rJWArLV4hISstKisrJCsqLT09KiskLS0tPT0hKyorJC09PSsrJCskLT09KyslXi0rYCEqLV4q""",
        """XitgYCQrYCReKy1gXisqLSorYGArKyUqIS1eXz0rKiVfK2BfXyslJF8rJCQ9KyQhKystKisrYCQtKy0qXy1eISsrJSUlLV4rJSsqX18rYCUqK2AqXyslJCEtPT0hKyUkKisqKiErYCEhKypgLSsqLSErYF8kLV4qPSsrKz0rKyVfK2BeJStgXiQrKl9fKyslXystKyor""",
        """JCpeKyQlIS1eISsrLSorLV4rXyslJV8rLS1fK2BfPS1eLSErJSUtKy0lLSslJS0rYCEtLT09KisrLV8rYCsrKyUhKi1eKy0tXiQlKyoqLSskISUrLSorLV4hXisqJCsrJCsqKyUhKy1eXysrKiErKyUqPS1eKyErJCEhLT09JCskISsrK19eK2A9XytgJCUrJCQqKyQl""",
        """LSsrLS0rYC0qK2BfISsrLV8rK18lKyUlISslXiotPT0qK2AkPStgXl8rYCQqKyQkXysrIT0rYCohKyVfLSskKl4tXisrLT09JCslXyUrLWA9LV4kLSstKiQrJSQqKyUrKytgISQrLSteK2BeIS1eISQrK18kK2AtLStgXz0rLSpfKyorXytgLSorJCtfLT09XislKior""",
        """YCQtKyUhLS1eXz0rLSstLT09ISslXl8rKl8hKyQkJCsqKiotXi0rKy1gJCstIS0tXiUhKyo9KisqIV4rKl4qKys9XyskYD0rKyQ9LV5gKiskLV4rJGAkKyRgPS0hXi0rJD0qKy1fJC1fXi0tIV5fKyo9XisqJC0rJCU9KypeKisrKyUrKyQtKyo9Xy0hXiQtPS0rKyRf""",
        """Xy0hXi0rKiQhKypePSsrKiErKyQtKypeIS0hXiUtPS0hLSFeXysrJSErKj09KysrKy0hXl8tX14kLSFeKisqLV4rKyUhKystJCsrJF8rKi0hKyo9LS0hXiUtPS0kKyRfXy0hXiEtXisrLV4tPS09YF4tXi1fLSFeKy09LSstIV4qLV4rKi1eLSUtPWAqLV4tXislKiot""",
        """ISFeK2AqJStgIT0rYC0tLSFeXi1fXi0tIV4tLV49Xi1eKiEtXi0hLSFeKy09LSUtIV49LV49Xi1eKiotXi1fKyUqLS0hISUrYCEkK2AtIS0hXistX14kLSFeLS1eJCstXl4rLV4tKy0hXi0tPS09LSFeLS1eJC0tXl4tLV4tKislKl8tISElKyVfIStgLT0tIV4hKyQ9""",
        """Ky1fXistIV5eKyRgISskJD0rKiEkKyskKi0hXj0tPS1eLSFeJCsrJSorKiFeKyohXysqLSErKz0qKysrKisrJSQrJGBeKys9KysqJD0rKio9Ky0hXi1eJSErJColLV89ISsqKysrKiFfKyskJSsrXyorJT0rKyUhKislYCQtIV5fLV9eIS0hXl8rKyErKys9XisqLT0r""",
        """KyQ9LSFePS09LV8tIV49KyteISskYF4rJGAhKyohLSsqPSstPS0tKy0hJS1eJSErLSEhLV4lKyskKyQrKyEkKystJSsqXiUrKys9KyohISsrXyorKysqKytfJCsrK18rJCsqKypgXisqXiErKl9eKyteIS1eYCorK18tKypeLSsqJCErKiRfKyQtISskJCstXmAlKyor""",
        """LSsqJF4rKiokKyo9LSskYCErKyRfKypeISstISstXiUlKyo9XiskYCQrKl49KyskPSsrJV4rKis9Ky0hXi1eJT0rLSorLV4qISstJSUrJCQtLT09XislJCUrJCpeLV4tIStgYCsrJSReKypfXiskJCQrLSotKyQrXitgLSorKisrKy0qISskISQrYC0tKyorXytgYCEr""",
        """JV4tKy1gPSsqJSorLSokK2AkLStgLV8rKi0qKyUlKislJCorLWBfLV4tXistKiUtXipfK2AhKysqLSErLSoqK2AkXyskJCorJCsrLT09XyskISErYF8rKyQkKy09PS0rJCslLT09KyskK14rYGAqKyVeXislIV4rKmBfKyVgKytgJF8rYCFeLV4rKislYCorJCsqKyVf""",
        """XyskKiorJWA9KyorKytgLSorKi1fKyUlIStgXl8rJWBeLV4hPStgYCorYCQqKy0qLSsqYD0rJSUrKyQrXiskKiErKitfLT09KitgJC0rLSo9LV5fJSslJSQrJSQrKyVgJCsqK14tPT0kK2AqKi1eXyQtXiEqLT09JS1eKl4rJCReKyteXyslJV4rKyUrKyUlJCsqPT0r""",
        """YCs9KyQhPS09JV4rKj0tK2AkKiskKystPT0hKyotISsqXyErJCo9K2ArIS09PSErYCtfLV4qXyslJSorJCFeLT09XislJC0rJCUhKyUrPSstK18rJColLT09LSslKiotPT0hKyslJSsrKyorJCsqK2BeISslPSQtPT0rK2BeXitgKl8rYD1eKyVfPSslYCorYC0qKyQk""",
        """JCsrKyUrYF8qKyohXytgXz0rYF9eKy0tLSstKj0tXi1fKyQlJSskKy0rKiEhK2AkKitgKyorJC0rKy0qJStgXiQtPT0rLV4hPStgKystXistLT0lJSslJS0rYCo9KyslIStgK14rYCstK2AtKy1eKiotPT1fK2BgIStgISQtXi0rKyVfXyskJD0rYCQkK2BfJS09PSUr""",
        """Kj0tK2AkJS1eKyorJV8qKyVeKislJS0tXislKyRgPSsqLSstPT0kLV4qJCsqX18tXio9K2AhKysrJSErJSUhKyorXisqXysrJSQlLT09Xi1eXyUtPT0lK2AhISskJCQtXiElK2AqPStgPSQrKys9KyshJCtgLV8rJCohKyVgJSstIT0tXiVfK2BgKislXiorYC0qLV4h""",
        """JCstKj0rJCslKyQkLSskJF4rJWAtKyQrXyslIV8tXislK2BgXytgJCUrYC1eLV4rJSstKiQrJV4tKy0qJC1eJCorJSUqKyUkXitgISUtXl9eLT09XyskKyUrJCokLV4kJCslYD0rKisrKyQlLSskKiQrLSokKyVeXytgIV4rKiUhKy0qJCskIV4rYC1fLV5fKislYCEr""",
        """YCQlKy0lXiskJD0rLSoqKyQrJCskJF4rJCU9LT09JS1eKl4rJV8qKyQqJCstKj0rYCQlK2AhLSsqKy0rJSUlK2AkJSskYC0tXl8lKy0qXysqK18rJSUlKyQhXy09PSorYCQqK2AtKy1eLV8tPT0lKyQrISstKi0tXiEhK2BgLStgJCUrJSUkKyQlKystKiUrJCs9LT09""",
        """ISskIS0tPT0rKyQhJStgLS0rKiUrKyVgKytgKi0tXl8qK2AqLStgJC0rYF8kKyUlXyslJF4rLSolLV4rPSslK14rKyUrKypfKi1eKiQtPT1eKyUkJStgISQrJCo9KyUlJStgKyorKl89LV4qPSslJSstXisqKy0qXi1eLV4tPT0hKy1gIStgJC0rYF8lKyUrLS1eJD0r""",
        """LSslKyQrISslJS0rKyohKyohKiskISErYF8rK2A9IS09PSQrYCQhKyQkJCskKi0rYF8hKyUhISsrKysrLSVeKyokPStgPSUrJCQqK2A9XysqJC0tXishKyRgIS1eKiQrKl4tK2BfIS09PSErJT0tK2AhLSslXl4rKl8lKypgPStgJD0rKiskLT09PS1eJCorJWAkKyQq""",
        """PSsrIS0tXiRfKyokKitgISsrKyskKy0rKitgJCsrYCEtKyUlXitgJCorKiotK2AkXislLSQrKiUtKy0qXislXl8tXl89KyQrKitgLS0rJCEtLT09PSskLSEtPT0rKyslJCskJCsrYCQrK2BfXyslXiUrLSVfKyQkKysqISUrJSEqLV4rKisrXz0rYCRfKyQqXy1eKz0r""",
        """LWBeK2BfLS1eJCUrLSU9KyoqISslJSUrJSErKypgLSsqLSsrYF8qLV4tIS09YD0tXmBfKyorXy1eJF8rJCUrLT1gXi0hXiorJD0rKyQ9Xy09KyUrKmAtKyQrXisqISQrKi0qKyslKiskJCQrKyReKypeXy1eYCErKiUhKyskKyskJD0tISFfLT0kKy0hIS0tXyskLV4k""",
        """XislXyUrLSslKyVgXysrLT0rJSEkKyokXi1eXiUrJSUqKyUtKy1eXy0rKyteK2BgPStgLV4rJCotKyQrXysqIT0rLSoqKyteJC1eJF4rKyolKyotXi1eKyorK18hKystJS1eLT0rKi0rKyUlISslXiUrJT0tKyUrJSsqJCUrKj0lLV4kJS1eK14rJSUtLV5fKisrXiEr""",
        """KiQhLT09XysqLV8rKyEhLV4hPS09JCUtXytfLT0rXyskLV8rKyUqKypeXi0hIV4rKiFfKyotISsrJSUrJCReKyskKysqXj0tISEkLT0kKy0hISsrYF4rKyQlJCsrPS0rKysrKyQkPStgJSQrKiQqKysqXisrJF4rKj0qLV5gPStgXi0rLSUkKyUqKysqLSsrKyUhKyQk""",
        """PSsrJCUrKl4qLV8qLS1fKyErKmAhKysrPS1fPSUrKiEtKyotLSsrJSorJCReKyskLSsqXj0tXystLV9eJS0hIT0rKytfKyokXysqKl4rKyErKys9ISsrXystXyRfLT0rfgh""",
    ).joinToString("")

        val decoded = JuicyCodesDecoder.decode(blob)

        assertTrue(decoded.contains("JuicyCodes.JWPlayer('jc-player', config)"))
        val config = JuicyCodesDecoder.config(decoded)
        assertEquals("Karna the Guardian S01E01 [Hindi]", config.title)
        assertEquals(
            "https://wfbrcpgcgcwjrqh.groovy.monster/stream/Z2WyAQx0BQqyZwDmZzDmBTVkZGDlMQV0Z2HlZGywAzIyAwAwBTRjLGH1LwSxLmDlMJL4BGZjMwxmAGZ5MQLmAF54A2yhMaMsEz9sGwAlqxEAE2MzAQuOYxAPAacwJUAJFKSLDycIpIIXZ0uwpGEvZJA4E19MFaEED2ABH0SyGIAsG1STM1tlA2q2HaMmqQA5AHy4FKcfDxL/BTD4ZwyyLwR1BGD1ZTZ3MQH5Awx3LmuxZTHkZzD5LGWyZwyuA2SxZGHmMGt5ZmMzAGD0AwZ4BGMuZwAzAzDkLF5FGIMQZ1Oaq2AQHxMEq2M1Z0AVGIO3YwMdpzIKAGyxIRcWoKyKo1t2rIAUHTqjGmA3Lxf3oHcYGHMGnGNkZT5wDzAvAayGITWypR1gGx1VI3WnMRjlI08.m3u8",
            config.hlsUrl,
        )
        assertEquals(
            mapOf("1080" to "1080P FHD", "720" to "720P HD", "360" to "360P SD"),
            config.qualityLabels,
        )
        assertTrue(config.thumbnailsVtt!!.startsWith("https://wfbrcpgcgcwjrqh.groovy.monster/images/"))
        assertTrue(config.thumbnailsVtt.endsWith("sprite.vtt"))
    }
}
