package com.example.wechatsales.repository;

import com.example.wechatsales.domain.Contact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    Optional<Contact> findByExternalUserId(String externalUserId);
}
