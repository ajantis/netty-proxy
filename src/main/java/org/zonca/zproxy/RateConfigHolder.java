package org.zonca.zproxy;
import com.google.common.util.concurrent.RateLimiter;
import org.apache.commons.io.IOUtils;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Dmitry Ivanov (dmitry.ivanov@reltio.com)
 *         Reltio, Inc.
 */
public enum RateConfigHolder {

    INSTANCE;

    private final Map<String, RateLimiter> rateConfig;

    private RateConfigHolder(){
        System.out.println("Starting rate config holder...");
        Map<String, RateLimiter> config = null;
        try {
            config = readConfig();
        } catch (Exception e){
            System.out.println("Exception during reading config from file: " + e.getMessage());
        }
        rateConfig = config == null ? Collections.<String, RateLimiter>emptyMap() : config;

    }

    private Map<String, RateLimiter> readConfig() throws Exception{
        final InputStream is = this.getClass().getClassLoader().getResourceAsStream("tenants.props");

        @SuppressWarnings("unchecked")
        final List<String> lines = IOUtils.readLines(is);

        final Map<String, RateLimiter> map = new HashMap<String, RateLimiter>();

        for (String line : lines){
            String tenant = line.split("=")[0];
            Double rate = Double.parseDouble(line.split("=")[1]);
            final RateLimiter limiter = RateLimiter.create(rate);
            map.put(tenant, limiter);
        }

        return map;
    }

    public Map<String, RateLimiter> getRateConfig(){
        return rateConfig;
    }
}
