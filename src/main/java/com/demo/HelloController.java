package com.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;

@Controller
public class HelloController {

    @Value("${app.version:unknown}")
    private String appVersion;

    private final long startTime = System.currentTimeMillis();

    @GetMapping("/")
    public String dashboard(Model model) throws Exception {
        long uptimeMillis = System.currentTimeMillis() - startTime;
        Duration uptime = Duration.ofMillis(uptimeMillis);

        model.addAttribute("version",   appVersion);
        model.addAttribute("hostname",  InetAddress.getLocalHost().getHostName());
        model.addAttribute("podName",   System.getenv().getOrDefault("HOSTNAME", "unknown"));
        model.addAttribute("namespace", readFile("/var/run/secrets/kubernetes.io/serviceaccount/namespace", "unknown"));
        model.addAttribute("uptime",    String.format("%dd %02dh %02dm %02ds",
                uptime.toDays(), uptime.toHoursPart(), uptime.toMinutesPart(), uptime.toSecondsPart()));
        model.addAttribute("javaVersion", System.getProperty("java.version"));
        model.addAttribute("cpus",      Runtime.getRuntime().availableProcessors());
        model.addAttribute("memTotal",  Runtime.getRuntime().totalMemory() / 1024 / 1024 + " MB");
        model.addAttribute("memFree",   Runtime.getRuntime().freeMemory()  / 1024 / 1024 + " MB");

        return "dashboard";
    }

    @GetMapping("/health")
    @ResponseBody
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    private String readFile(String path, String fallback) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))).trim();
        } catch (Exception e) {
            return fallback;
        }
    }
}
