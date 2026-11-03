package dev.clashsub.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Only known browser routes receive the SPA; API, subscriptions and missing assets retain their status. */
@Controller
public class SpaController {
    @GetMapping({"/login", "/profiles", "/profiles/{id}", "/sources", "/nodes", "/import"})
    public String application() { return "forward:/index.html"; }
}
