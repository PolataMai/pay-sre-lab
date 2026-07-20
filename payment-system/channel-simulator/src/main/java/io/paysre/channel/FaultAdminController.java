package io.paysre.channel;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/admin/faults")
public final class FaultAdminController {

    private static final Logger LOGGER = LoggerFactory.getLogger(FaultAdminController.class);

    private final FaultRuleRepository repository;

    public FaultAdminController(FaultRuleRepository repository) {
        this.repository = repository;
    }

    @PutMapping("/{channel}")
    public void replace(@PathVariable String channel, @RequestBody FaultRule request) {
        if (!channel.equals(request.channel())) {
            throw new IllegalArgumentException("path channel must match rule channel");
        }
        repository.replace(request);
        LOGGER.atInfo()
                .addKeyValue("event", "CHANNEL_FAULT_RULE_REPLACED")
                .addKeyValue("channel", request.channel())
                .addKeyValue("faultType", request.type().name())
                .addKeyValue("activeFrom", request.activeFrom())
                .addKeyValue("activeUntil", request.activeUntil())
                .log("Channel fault rule replaced");
    }
}
