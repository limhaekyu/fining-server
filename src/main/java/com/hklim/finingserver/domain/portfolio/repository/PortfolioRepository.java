package com.hklim.finingserver.domain.portfolio.repository;

import com.hklim.finingserver.domain.member.entity.Member;
import com.hklim.finingserver.domain.portfolio.entity.Portfolio;
import com.hklim.finingserver.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {
    boolean existsByMemberAndStock(Member member, Stock stock);
}