package com.vehiclerental.mak.rentals.repository;

import com.vehiclerental.mak.rentals.entity.RentalPayment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RentalPaymentRepository extends JpaRepository<RentalPayment, Long> {}
