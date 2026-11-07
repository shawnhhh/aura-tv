package tv.herrin.app

import org.junit.Assert.*
import org.junit.Test

class P0FailureTraceTest {
    @Test fun preservesCauseAndSourceLocationButNeverMessagesOrFilePayloads() {
        val secret="https://private.invalid/?token=secret cookie=password"
        val root=UnsatisfiedLinkError(secret).apply {
            stackTrace=arrayOf(StackTraceElement("com.github.catvod.Spider", "init", secret, 42))
        }
        val error=java.lang.reflect.InvocationTargetException(root,secret)
        val output=SpiderFailureTrace.structure(error)
        assertTrue(output.contains("java.lang.UnsatisfiedLinkError"))
        assertTrue(output.contains("com.github.catvod.Spider.init:42"))
        for(value in listOf("https://","secret","cookie=","password"))assertFalse(output.contains(value))
    }
}
