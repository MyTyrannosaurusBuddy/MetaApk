package com.meta.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.meta.app.core.WebFetch;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SSRF guard behaviour on the device: private / loopback / link-local
 * targets must be refused without a connection, and a public fetch must
 * actually work over the device network (the OkHttp SSRF-Dns path).
 */
@RunWith(AndroidJUnit4.class)
public class WebFetchSsrfDeviceTest {

    @Test
    public void loopbackAndLanAreRefused() {
        assertTrue("loopback must be refused",
            WebFetch.fetch("http://127.0.0.1:8080/").startsWith("Error:"));
        assertTrue("localhost must be refused",
            WebFetch.fetch("http://localhost:8080/").startsWith("Error:"));
        assertTrue("RFC1918 must be refused",
            WebFetch.fetch("http://192.168.1.1/").startsWith("Error:"));
        assertTrue("10/8 must be refused",
            WebFetch.fetch("http://10.0.0.1/").startsWith("Error:"));
        assertTrue("link-local must be refused",
            WebFetch.fetch("http://169.254.169.254/latest/meta-data/").startsWith("Error:"));
        StringBuilder err = new StringBuilder();
        assertTrue("binary loopback must be refused",
            WebFetch.fetchBinary("http://127.0.0.1:8080/", err) == null
                && err.toString().contains("private"));
    }

    @Test
    public void publicUrlFetchesOverTheDeviceNetwork() {
        String text = WebFetch.fetch("https://example.com/");
        assertFalse("Public fetch failed: " + text, text.startsWith("Error:"));
        assertTrue(text.contains("Example Domain"));
    }
}
