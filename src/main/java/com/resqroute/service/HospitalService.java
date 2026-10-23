package com.resqroute.service;

import com.resqroute.exception.InvalidCoordinatesException;
import com.resqroute.exception.InvalidRequestException;
import com.resqroute.exception.NotFoundException;
import com.resqroute.model.Hospital;
import com.resqroute.repository.HospitalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Hospital network management with input validation.
 */
@Service
public class HospitalService {

    private static final Logger log = LoggerFactory.getLogger(HospitalService.class);

    private final HospitalRepository hospitalRepository;

    public HospitalService(HospitalRepository hospitalRepository) {
        this.hospitalRepository = hospitalRepository;
    }

    public List<Hospital> findAll() {
        return hospitalRepository.findAll();
    }

    public Hospital findById(Long id) {
        return hospitalRepository.findById(id)
                .orElseThrow(() ->
                        new NotFoundException("Hospital not found with id " + id));
    }

    @Transactional
    public Hospital addHospital(Hospital hospital) {
        if (hospital.getName() == null || hospital.getName().isBlank()) {
            throw new InvalidRequestException("Hospital name is required.");
        }

        validateCoordinates(hospital.getLatitude(), hospital.getLongitude());

        Hospital saved = hospitalRepository.save(hospital);
        log.info("Hospital #{} ({}) added", saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public Hospital updateHospital(Long id, Hospital update) {
        Hospital hospital = findById(id);

        if (update.getName() == null || update.getName().isBlank()) {
            throw new InvalidRequestException("Hospital name is required.");
        }

        validateCoordinates(update.getLatitude(), update.getLongitude());

        hospital.setName(update.getName());
        hospital.setLocation(update.getLocation());
        hospital.setLatitude(update.getLatitude());
        hospital.setLongitude(update.getLongitude());

        return hospitalRepository.save(hospital);
    }

    private void validateCoordinates(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw new InvalidCoordinatesException(
                    "Hospital coordinates (latitude and longitude) are required.");
        }

        if (latitude < -90 || latitude > 90
                || longitude < -180 || longitude > 180) {
            throw new InvalidCoordinatesException(
                    "Hospital coordinates out of range: "
                            + latitude + ", " + longitude);
        }
    }
}
