package com.interviewprep.curriculum;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface TopicRepository extends JpaRepository<Topic, String> {

  List<Topic> findAllByOrderByDomainIdAscSortOrderAsc();
}
