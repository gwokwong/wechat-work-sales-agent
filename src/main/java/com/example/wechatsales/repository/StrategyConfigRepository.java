package com.example.wechatsales.repository;

import com.example.wechatsales.strategy.StrategyConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategyConfigRepository extends JpaRepository<StrategyConfig, Long> {

    List<StrategyConfig> findByStageAndEnabledTrueOrderByPriorityAsc(String stage);
}
