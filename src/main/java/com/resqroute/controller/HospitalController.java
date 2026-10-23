package com.resqroute.controller;

import com.resqroute.model.Hospital;
import com.resqroute.service.HospitalService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Hospital network endpoints (staff-only, authenticated).
 */
@RestController
@RequestMapping("/hospital")
public class HospitalController {

    private final HospitalService hospitalService;

    public HospitalController(HospitalService hospitalService) {
        this.hospitalService = hospitalService;
    }

    @PostMapping
    public Hospital addHospital(@RequestBody Hospital hospital) {
        return hospitalService.addHospital(hospital);
    }

    @GetMapping
    public List<Hospital> getAllHospitals() {
        return hospitalService.findAll();
    }

    @PutMapping("/{id}")
    public Hospital updateHospital(@PathVariable Long id,
                                    @RequestBody Hospital update) {
        return hospitalService.updateHospital(id, update);
    }
}
