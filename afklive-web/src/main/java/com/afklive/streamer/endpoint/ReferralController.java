package com.afklive.streamer.endpoint;

import com.afklive.streamer.exception.NotFoundException;
import com.afklive.streamer.model.User;
import com.afklive.streamer.repository.UserRepository;
import com.afklive.streamer.service.ReferralService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/** GR-18: the creator's invite link, who joined through it, and the opt-in credit line. */
@RestController
@RequestMapping("/api/referrals")
public class ReferralController {

    private final ReferralService referrals;
    private final UserRepository users;

    public ReferralController(ReferralService referrals, UserRepository users) {
        this.referrals = referrals;
        this.users = users;
    }

    public record CreditLineRequest(boolean enabled) {
    }

    private User me(Principal principal) {
        return users.findByEmail(principal.getName()).orElseThrow(() -> new NotFoundException("Account not found."));
    }

    @GetMapping
    public ReferralService.Summary summary(Principal principal) {
        return referrals.summary(me(principal).getId());
    }

    @PutMapping("/credit-line")
    public ReferralService.Summary creditLine(@RequestBody CreditLineRequest req, Principal principal) {
        Long id = me(principal).getId();
        referrals.setCreditLine(id, req.enabled());
        return referrals.summary(id);
    }
}
