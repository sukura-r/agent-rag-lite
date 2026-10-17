package com.example.agentrag.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ConsolePageController {
    @GetMapping({"/", "/index.html"})
    public String home() {
        return "redirect:/console/";
    }

    @GetMapping("/knowledge.html")
    public String documents() {
        return "redirect:/console/#/documents";
    }

    @GetMapping({"/console", "/console/"})
    public String console() {
        return "forward:/console/index.html";
    }
}
