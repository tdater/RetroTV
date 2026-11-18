package com.aiconsole.retrotv

import com.aiconsole.retrotv.web.Surrogates
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SurrogatesCmpTest {
    @Test fun consentManagersGetAStandIn() {
        assertNotNull(Surrogates.find("cdn.cookielaw.org", "/scripttemplates/otSDKStub.js"))
        assertNotNull(Surrogates.find("fundingchoicesmessages.google.com", "/i/pub-1"))
        assertNotNull(Surrogates.find("cdn.example.com", "/cmp/stub.js"))
        assertNotNull(Surrogates.find("cdn.example.com", "/tcf-v2.js"))
        assertNotNull(Surrogates.find("cdn.example.com", "/js/cmp2.js"))
    }

    @Test fun otherWordsContainingTheLettersDoNot() {
        assertNull(Surrogates.find("cdn.example.com", "/js/cmpny.js"))
        assertNull(Surrogates.find("cdn.example.com", "/js/xtcfoo.js"))
        assertNull(Surrogates.find("cdn.example.com", "/tracker.js"))
    }

    @Test fun adStandInsStillWinOverCmp() {
        val gpt = Surrogates.find("securepubads.g.doubleclick.net", "/tag/js/gpt.js")
        assertNotNull(gpt)
        assertNull(Surrogates.find("ads.example.com", "/x/ad.js"))
    }
}
