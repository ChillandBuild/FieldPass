package com.fieldpass.student.data

import com.fieldpass.student.data.settings.BaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaseUrlTest {
    @Test fun addsHttpToBareHostAndPort() {
        assertEquals("http://192.168.43.10:8000", BaseUrl.normalize("192.168.43.10:8000"))
    }

    @Test fun stripsPathAndTrailingSlashAndWhitespace() {
        assertEquals("http://192.168.43.10:8000", BaseUrl.normalize("  http://192.168.43.10:8000/api/v1/  "))
    }

    @Test fun keepsHttpsAndDropsDefaultPort() {
        assertEquals("https://fieldpass.example.com", BaseUrl.normalize("https://fieldpass.example.com:443/"))
    }

    @Test fun rejectsGarbageAndOtherSchemes() {
        assertNull(BaseUrl.normalize(""))
        assertNull(BaseUrl.normalize("   "))
        assertNull(BaseUrl.normalize("ftp://host"))
        assertNull(BaseUrl.normalize("http://"))
    }
}
