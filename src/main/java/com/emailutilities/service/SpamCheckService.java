package com.emailutilities.service;

import org.springframework.stereotype.Service;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SpamCheckService {

    // Spamhaus DNS-based blocklists
    private static final String SPAMHAUS_ZEN = "zen.spamhaus.org";      // Combined IP blocklist
    private static final String SPAMHAUS_DBL = "dbl.spamhaus.org";      // Domain blocklist

    // Cache results for 1 hour to avoid excessive DNS queries
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 3600000; // 1 hour

    // Spamhaus return codes - see https://www.spamhaus.org/faq/section/DNSBL%20Usage
    private static final Map<String, String> ZEN_CODES = Map.ofEntries(
        Map.entry("127.0.0.2", "SBL - Spamhaus Block List (known spam source)"),
        Map.entry("127.0.0.3", "SBL - Spamhaus Block List (known spam source)"),
        Map.entry("127.0.0.4", "XBL - Exploits Block List (hijacked PC/botnet)"),
        Map.entry("127.0.0.5", "XBL - Exploits Block List (hijacked PC/botnet)"),
        Map.entry("127.0.0.6", "XBL - Exploits Block List (hijacked PC/botnet)"),
        Map.entry("127.0.0.7", "XBL - Exploits Block List (hijacked PC/botnet)"),
        Map.entry("127.0.0.9", "SBL - Spamhaus DROP/EDROP (hijacked IP space)"),
        Map.entry("127.0.0.10", "PBL - Policy Block List (dynamic IP)"),
        Map.entry("127.0.0.11", "PBL - Policy Block List (dynamic IP)")
    );

    private static final Map<String, String> DBL_CODES = Map.ofEntries(
        Map.entry("127.0.1.2", "DBL - Spam domain"),
        Map.entry("127.0.1.4", "DBL - Phishing domain"),
        Map.entry("127.0.1.5", "DBL - Malware domain"),
        Map.entry("127.0.1.6", "DBL - Botnet C&C domain"),
        Map.entry("127.0.1.102", "DBL - Abused legit spam"),
        Map.entry("127.0.1.103", "DBL - Abused spammed redirector"),
        Map.entry("127.0.1.104", "DBL - Abused legit phish"),
        Map.entry("127.0.1.105", "DBL - Abused legit malware"),
        Map.entry("127.0.1.106", "DBL - Abused legit botnet C&C")
    );

    /**
     * Check if an IP address is listed in Spamhaus ZEN
     */
    public SpamCheckResult checkIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return new SpamCheckResult(false, null, "Invalid IP");
        }

        // Check cache first
        String cacheKey = "ip:" + ipAddress;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.result;
        }

        try {
            // Reverse the IP for DNS query
            String[] octets = ipAddress.split("\\.");
            if (octets.length != 4) {
                return new SpamCheckResult(false, null, "Invalid IPv4 format");
            }

            String reversedIp = octets[3] + "." + octets[2] + "." + octets[1] + "." + octets[0];
            String query = reversedIp + "." + SPAMHAUS_ZEN;

            String[] results = dnsLookup(query);
            if (results != null && results.length > 0) {
                String code = results[0];
                String reason = ZEN_CODES.getOrDefault(code, "Listed in Spamhaus ZEN (" + code + ")");
                SpamCheckResult result = new SpamCheckResult(true, code, reason);
                cache.put(cacheKey, new CacheEntry(result));
                return result;
            }

            SpamCheckResult result = new SpamCheckResult(false, null, "Not listed");
            cache.put(cacheKey, new CacheEntry(result));
            return result;

        } catch (Exception e) {
            return new SpamCheckResult(false, null, "Lookup error: " + e.getMessage());
        }
    }

    /**
     * Check if a domain is listed in Spamhaus DBL
     */
    public SpamCheckResult checkDomain(String domain) {
        if (domain == null || domain.isEmpty()) {
            return new SpamCheckResult(false, null, "Invalid domain");
        }

        // Normalize domain
        domain = domain.toLowerCase().trim();
        if (domain.startsWith("www.")) {
            domain = domain.substring(4);
        }

        // Check cache first
        String cacheKey = "domain:" + domain;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.result;
        }

        try {
            String query = domain + "." + SPAMHAUS_DBL;
            String[] results = dnsLookup(query);

            if (results != null && results.length > 0) {
                String code = results[0];
                String reason = DBL_CODES.getOrDefault(code, "Listed in Spamhaus DBL (" + code + ")");
                SpamCheckResult result = new SpamCheckResult(true, code, reason);
                cache.put(cacheKey, new CacheEntry(result));
                return result;
            }

            SpamCheckResult result = new SpamCheckResult(false, null, "Not listed");
            cache.put(cacheKey, new CacheEntry(result));
            return result;

        } catch (Exception e) {
            return new SpamCheckResult(false, null, "Lookup error: " + e.getMessage());
        }
    }

    /**
     * Check an email address (checks the domain part)
     */
    public SpamCheckResult checkEmail(String email) {
        if (email == null || !email.contains("@")) {
            return new SpamCheckResult(false, null, "Invalid email");
        }

        String domain = email.substring(email.lastIndexOf("@") + 1);
        return checkDomain(domain);
    }

    /**
     * Batch check multiple emails/domains
     */
    public Map<String, SpamCheckResult> checkEmails(List<String> emails) {
        Map<String, SpamCheckResult> results = new LinkedHashMap<>();
        Set<String> checkedDomains = new HashSet<>();

        for (String email : emails) {
            if (email == null || !email.contains("@")) continue;

            String domain = email.substring(email.lastIndexOf("@") + 1).toLowerCase();

            // Avoid checking the same domain multiple times
            if (checkedDomains.contains(domain)) {
                results.put(email, results.get(checkedDomains.stream()
                    .filter(d -> d.equals(domain))
                    .findFirst()
                    .map(d -> emails.stream()
                        .filter(e -> e.toLowerCase().endsWith("@" + d))
                        .findFirst()
                        .orElse(email))
                    .orElse(email)));
                continue;
            }

            SpamCheckResult result = checkDomain(domain);
            results.put(email, result);
            checkedDomains.add(domain);
        }

        return results;
    }

    /**
     * Get cache statistics
     */
    public Map<String, Object> getCacheStats() {
        long validEntries = cache.values().stream().filter(e -> !e.isExpired()).count();
        return Map.of(
            "totalEntries", cache.size(),
            "validEntries", validEntries,
            "expiredEntries", cache.size() - validEntries
        );
    }

    /**
     * Clear the cache
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Perform DNS lookup
     */
    private String[] dnsLookup(String query) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "2000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");

            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(query, new String[]{"A"});
            ctx.close();

            if (attrs.get("A") != null) {
                String result = attrs.get("A").get().toString();
                return new String[]{result};
            }
        } catch (NamingException e) {
            // NXDOMAIN means not listed - this is expected for clean IPs/domains
            if (!e.getMessage().contains("NXDOMAIN")) {
                System.err.println("DNS lookup error for " + query + ": " + e.getMessage());
            }
        }
        return null;
    }

    // Result class
    public static class SpamCheckResult {
        private final boolean listed;
        private final String code;
        private final String reason;

        public SpamCheckResult(boolean listed, String code, String reason) {
            this.listed = listed;
            this.code = code;
            this.reason = reason;
        }

        public boolean isListed() { return listed; }
        public String getCode() { return code; }
        public String getReason() { return reason; }

        public Map<String, Object> toMap() {
            return Map.of(
                "listed", listed,
                "code", code != null ? code : "",
                "reason", reason != null ? reason : ""
            );
        }
    }

    // Cache entry with TTL
    private static class CacheEntry {
        final SpamCheckResult result;
        final long timestamp;

        CacheEntry(SpamCheckResult result) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
