package com.interviewprep.curriculum;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DomainController {

  private final DomainCatalog catalog;

  DomainController(DomainCatalog catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/api/domains")
  List<DomainSummary> domains() {
    return catalog.all();
  }
}
