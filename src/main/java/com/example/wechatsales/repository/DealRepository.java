package com.example.wechatsales.repository;

import com.example.wechatsales.domain.Deal;
import com.example.wechatsales.domain.SalesStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DealRepository extends JpaRepository<Deal, Long> {

    Optional<Deal> findByContactId(Long contactId);

    List<Deal> findByStage(SalesStage stage);
}
