package com.learning.spring.financialdashboard.service;

import com.learning.spring.financialdashboard.exception.NotFoundException;
import com.learning.spring.financialdashboard.exception.ValidationException;
import com.learning.spring.financialdashboard.model.Account;
import com.learning.spring.financialdashboard.model.AccountType;
import com.learning.spring.financialdashboard.model.VehicleDetail;
import com.learning.spring.financialdashboard.repository.AccountRepository;
import com.learning.spring.financialdashboard.repository.VehicleDetailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.List;

/** The number plate and year bought of vehicle accounts. The vehicle's name and value are the account's own. */
@Service
@Transactional
public class VehicleService {

    private static final Logger log = LoggerFactory.getLogger(VehicleService.class);

    public static final int EARLIEST_YEAR = 1900;

    public record VehicleData(String numberPlate, Integer yearBought) {
    }

    private final VehicleDetailRepository details;
    private final AccountRepository accounts;

    public VehicleService(VehicleDetailRepository details, AccountRepository accounts) {
        this.details = details;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<VehicleDetail> list() {
        return details.findAll();
    }

    /** Sets or changes a vehicle's details. Both are optional; an empty plate and no year clear them. */
    public VehicleDetail save(Long accountId, VehicleData data) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Account " + accountId + " not found"));
        if (account.getType() != AccountType.VEHICLE) {
            throw new ValidationException("Account '" + account.getName() + "' is not a vehicle");
        }
        String plate = cleanPlate(data == null ? null : data.numberPlate());
        Integer year = data == null ? null : data.yearBought();
        if (year != null && (year < EARLIEST_YEAR || year > Year.now().getValue())) {
            throw new ValidationException("Year bought must be between " + EARLIEST_YEAR + " and " + Year.now().getValue());
        }
        VehicleDetail detail = details.findById(accountId).orElseGet(() -> new VehicleDetail(accountId, null, null));
        detail.setNumberPlate(plate);
        detail.setYearBought(year);
        log.info("Vehicle details saved: account={}", accountId);
        return details.save(detail);
    }

    /** Capital letters, single spaces, at most 20 characters; blank means none. */
    private String cleanPlate(String plate) {
        if (plate == null || plate.isBlank()) {
            return null;
        }
        String cleaned = plate.trim().replaceAll("\\s+", " ").toUpperCase();
        if (cleaned.length() > 20) {
            throw new ValidationException("The number plate is too long (20 characters at most)");
        }
        return cleaned;
    }
}
