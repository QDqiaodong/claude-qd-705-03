package com.brewery.craft.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.brewery.craft.domain.MaintenanceEvent;

public interface MaintenanceEventRepository extends JpaRepository<MaintenanceEvent, Long> {

    List<MaintenanceEvent> findAllByWindowIdOrderByIdAsc(Long windowId);
}
