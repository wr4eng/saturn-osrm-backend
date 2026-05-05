// src/main/java/org/saturn/MainModule.java

package org.saturn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsonp.JSONPModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.name.Names;
import com.nimbusds.oauth2.sdk.GeneralException;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import org.apache.velocity.app.VelocityEngine;
import org.saturn.broadcast.BroadcastService;
import org.saturn.broadcast.MulticastBroadcastService;
import org.saturn.broadcast.RedisBroadcastService;
import org.saturn.broadcast.NullBroadcastService;
import org.saturn.config.Config;
import org.saturn.config.Keys;
import org.saturn.database.LdapProvider;
import org.saturn.database.OpenIdProvider;
import org.saturn.database.StatisticsManager;
import org.saturn.forward.EventForwarder;
import org.saturn.forward.EventForwarderJson;
import org.saturn.forward.EventForwarderAmqp;
import org.saturn.forward.EventForwarderKafka;
import org.saturn.forward.EventForwarderMqtt;
import org.saturn.forward.PositionForwarder;
import org.saturn.forward.PositionForwarderJson;
import org.saturn.forward.PositionForwarderAmqp;
import org.saturn.forward.PositionForwarderKafka;
import org.saturn.forward.PositionForwarderRedis;
import org.saturn.forward.PositionForwarderUrl;
import org.saturn.forward.PositionForwarderMqtt;
import org.saturn.forward.PositionForwarderWialon;
import org.saturn.geocoder.AddressFormat;
import org.saturn.geocoder.BanGeocoder;
import org.saturn.geocoder.BingMapsGeocoder;
import org.saturn.geocoder.FactualGeocoder;
import org.saturn.geocoder.GeoapifyGeocoder;
import org.saturn.geocoder.GeocodeFarmGeocoder;
import org.saturn.geocoder.GeocodeXyzGeocoder;
import org.saturn.geocoder.Geocoder;
import org.saturn.geocoder.GisgraphyGeocoder;
import org.saturn.geocoder.GoogleGeocoder;
import org.saturn.geocoder.HereGeocoder;
import org.saturn.geocoder.LocationIqGeocoder;
import org.saturn.geocoder.MapQuestGeocoder;
import org.saturn.geocoder.MapTilerGeocoder;
import org.saturn.geocoder.MapboxGeocoder;
import org.saturn.geocoder.MapmyIndiaGeocoder;
import org.saturn.geocoder.NominatimGeocoder;
import org.saturn.geocoder.OpenCageGeocoder;
import org.saturn.geocoder.PositionStackGeocoder;
import org.saturn.geocoder.PlusCodesGeocoder;
import org.saturn.geocoder.TomTomGeocoder;
import org.saturn.geocoder.GeocodeJsonGeocoder;
import org.saturn.geolocation.GeolocationProvider;
import org.saturn.geolocation.GoogleGeolocationProvider;
import org.saturn.geolocation.OpenCellIdGeolocationProvider;
import org.saturn.geolocation.UniversalGeolocationProvider;
import org.saturn.geolocation.UnwiredGeolocationProvider;
import org.saturn.handler.CopyAttributesHandler;
import org.saturn.handler.FilterHandler;
import org.saturn.handler.GeocoderHandler;
import org.saturn.handler.GeolocationHandler;
import org.saturn.handler.SpeedLimitHandler;
import org.saturn.helper.LogAction;
import org.saturn.helper.ObjectMapperContextResolver;
import org.saturn.helper.WebHelper;
import org.saturn.mail.LogMailManager;
import org.saturn.mail.MailManager;
import org.saturn.mail.SmtpMailManager;
import org.saturn.session.cache.CacheManager;
import org.saturn.sms.HttpSmsClient;
import org.saturn.sms.SmsManager;
import org.saturn.sms.SnsSmsClient;
import org.saturn.speedlimit.OverpassSpeedLimitProvider;
import org.saturn.speedlimit.SpeedLimitProvider;
import org.saturn.storage.DatabaseStorage;
import org.saturn.storage.MemoryStorage;
import org.saturn.storage.Storage;
import org.saturn.web.WebServer;
import org.saturn.api.security.LoginService;
import org.saturn.routing.OsrmClient;
import org.saturn.routing.OsrmMatchClient;

import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainModule extends AbstractModule {

    private final String configFile;

    public MainModule(String configFile) {
        this.configFile = configFile;
    }

    @Override
    protected void configure() {
        bindConstant().annotatedWith(Names.named("configFile")).to(configFile);
        bind(Config.class).asEagerSingleton();
        bind(Timer.class).to(HashedWheelTimer.class).in(Scopes.SINGLETON);
    }

    @Singleton
    @Provides
    public static ExecutorService provideExecutorService() {
        return Executors.newCachedThreadPool();
    }

    @Singleton
    @Provides
    public static Storage provideStorage(Injector injector, Config config) {
        if (config.getBoolean(Keys.DATABASE_MEMORY)) {
            return injector.getInstance(MemoryStorage.class);
        } else {
            return injector.getInstance(DatabaseStorage.class);
        }
    }

    @Singleton
    @Provides
    public static ObjectMapper provideObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JSONPModule())
                .registerModule(new BlackbirdModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Singleton
    @Provides
    public static Client provideClient(ObjectMapperContextResolver objectMapperContextResolver) {
        return ClientBuilder.newClient().register(objectMapperContextResolver);
    }

    @Singleton
    @Provides
    public static SmsManager provideSmsManager(Config config, Client client) {
        if (config.hasKey(Keys.SMS_HTTP_URL)) {
            return new HttpSmsClient(config, client);
        } else if (config.hasKey(Keys.SMS_AWS_REGION)) {
            return new SnsSmsClient(config);
        }
        return null;
    }

    @Singleton
    @Provides
    public static MailManager provideMailManager(Config config, StatisticsManager statisticsManager) {
        if (config.getBoolean(Keys.MAIL_DEBUG)) {
            return new LogMailManager();
        } else {
            return new SmtpMailManager(config, statisticsManager);
        }
    }

    @Singleton
    @Provides
    public static LdapProvider provideLdapProvider(Config config) {
        if (config.hasKey(Keys.LDAP_URL)) {
            return new LdapProvider(config);
        }
        return null;
    }

    @Singleton
    @Provides
    public static OpenIdProvider provideOpenIDProvider(
            Config config, LoginService loginService, LogAction actionLogger)
            throws IOException, URISyntaxException, GeneralException {
        if (config.hasKey(Keys.OPENID_CLIENT_ID)) {
            return new OpenIdProvider(config, loginService, actionLogger);
        }
        return null;
    }

    @Provides
    public static WebServer provideWebServer(
            Injector injector, Config config) throws IOException {
        if (config.getInteger(Keys.WEB_PORT) > 0) {
            return new WebServer(injector, config);
        }
        return null;
    }

    @Singleton
    @Provides
    public static Geocoder provideGeocoder(Config config, Client client, StatisticsManager statisticsManager) {
        if (config.getBoolean(Keys.GEOCODER_ENABLE)) {
            String type = config.getString(Keys.GEOCODER_TYPE);
            String url = config.getString(Keys.GEOCODER_URL);
            String key = config.getString(Keys.GEOCODER_KEY);
            String language = config.getString(Keys.GEOCODER_LANGUAGE);
            String formatString = config.getString(Keys.GEOCODER_FORMAT);
            AddressFormat addressFormat = formatString != null ? new AddressFormat(formatString) : new AddressFormat();

            int cacheSize = config.getInteger(Keys.GEOCODER_CACHE_SIZE);
            Geocoder geocoder = switch (type) {
                case "pluscodes" -> new PlusCodesGeocoder();
                case "nominatim" -> new NominatimGeocoder(client, url, key, language, cacheSize, addressFormat);
                case "locationiq" -> new LocationIqGeocoder(client, url, key, language, cacheSize, addressFormat);
                case "gisgraphy" -> new GisgraphyGeocoder(client, url, cacheSize, addressFormat);
                case "mapquest" -> new MapQuestGeocoder(client, url, key, cacheSize, addressFormat);
                case "opencage" -> new OpenCageGeocoder(client, url, key, language, cacheSize, addressFormat);
                case "bingmaps" -> new BingMapsGeocoder(client, url, key, cacheSize, addressFormat);
                case "factual" -> new FactualGeocoder(client, url, key, cacheSize, addressFormat);
                case "geocodefarm" -> new GeocodeFarmGeocoder(client, key, language, cacheSize, addressFormat);
                case "geocodexyz" -> new GeocodeXyzGeocoder(client, key, cacheSize, addressFormat);
                case "ban" -> new BanGeocoder(client, cacheSize, addressFormat);
                case "here" -> new HereGeocoder(client, url, key, language, cacheSize, addressFormat);
                case "mapmyindia" -> new MapmyIndiaGeocoder(client, url, key, cacheSize, addressFormat);
                case "tomtom" -> new TomTomGeocoder(client, url, key, cacheSize, addressFormat);
                case "positionstack" -> new PositionStackGeocoder(client, key, cacheSize, addressFormat);
                case "mapbox" -> new MapboxGeocoder(client, key, cacheSize, addressFormat);
                case "maptiler" -> new MapTilerGeocoder(client, key, cacheSize, addressFormat);
                case "geoapify" -> new GeoapifyGeocoder(client, key, language, cacheSize, addressFormat);
                case "geocodejson" -> new GeocodeJsonGeocoder(client, url, key, language, cacheSize, addressFormat);
                default -> new GoogleGeocoder(client, url, key, language, cacheSize, addressFormat);
            };
            geocoder.setStatisticsManager(statisticsManager);
            return geocoder;
        }
        return null;
    }

    @Singleton
    @Provides
    public static GeolocationProvider provideGeolocationProvider(Config config, Client client) {
        if (config.getBoolean(Keys.GEOLOCATION_ENABLE)) {
            String type = config.getString(Keys.GEOLOCATION_TYPE, "google");
            String url = config.getString(Keys.GEOLOCATION_URL);
            String key = config.getString(Keys.GEOLOCATION_KEY);
            return switch (type) {
                case "opencellid" -> new OpenCellIdGeolocationProvider(client, url, key);
                case "unwired" -> new UnwiredGeolocationProvider(client, url, key);
                case "universal" -> new UniversalGeolocationProvider(client, url, key);
                default -> new GoogleGeolocationProvider(client, key);
            };
        }
        return null;
    }

    @Singleton
    @Provides
    public static SpeedLimitProvider provideSpeedLimitProvider(Config config, Client client) {
        if (config.getBoolean(Keys.SPEED_LIMIT_ENABLE)) {
            String type = config.getString(Keys.SPEED_LIMIT_TYPE, "overpass");
            String url = config.getString(Keys.SPEED_LIMIT_URL);
            return switch (type) {
                case "overpass" -> new OverpassSpeedLimitProvider(config, client, url);
                default -> throw new IllegalArgumentException("Unknown speed limit provider");
            };
        }
        return null;
    }

    @Singleton
    @Provides
    public static GeolocationHandler provideGeolocationHandler(
            Config config, @Nullable GeolocationProvider geolocationProvider, CacheManager cacheManager,
            StatisticsManager statisticsManager) {
        if (geolocationProvider != null) {
            return new GeolocationHandler(config, geolocationProvider, cacheManager, statisticsManager);
        }
        return null;
    }

    @Singleton
    @Provides
    public static GeocoderHandler provideGeocoderHandler(
            Config config, @Nullable Geocoder geocoder, CacheManager cacheManager) {
        if (geocoder != null) {
            return new GeocoderHandler(config, geocoder, cacheManager);
        }
        return null;
    }

    @Singleton
    @Provides
    public static SpeedLimitHandler provideSpeedLimitHandler(@Nullable SpeedLimitProvider speedLimitProvider) {
        if (speedLimitProvider != null) {
            return new SpeedLimitHandler(speedLimitProvider);
        }
        return null;
    }

    @Singleton
    @Provides
    public static CopyAttributesHandler provideCopyAttributesHandler(Config config, CacheManager cacheManager) {
        if (config.getBoolean(Keys.PROCESSING_COPY_ATTRIBUTES_ENABLE)) {
            return new CopyAttributesHandler(config, cacheManager);
        }
        return null;
    }

    @Singleton
    @Provides
    public static FilterHandler provideFilterHandler(
            Config config, CacheManager cacheManager, Storage storage, StatisticsManager statisticsManager) {
        if (config.getBoolean(Keys.FILTER_ENABLE)) {
            return new FilterHandler(config, cacheManager, storage, statisticsManager);
        }
        return null;
    }

    @Singleton
    @Provides
    public static BroadcastService provideBroadcastService(
            Config config, ExecutorService executorService, ObjectMapper objectMapper) throws IOException {
        if (config.hasKey(Keys.BROADCAST_TYPE)) {
            return switch (config.getString(Keys.BROADCAST_TYPE)) {
                case "multicast" -> new MulticastBroadcastService(config, executorService, objectMapper);
                case "redis" -> new RedisBroadcastService(config, executorService, objectMapper);
                default -> new NullBroadcastService();
            };
        }
        return new NullBroadcastService();
    }

    @Singleton
    @Provides
    public static EventForwarder provideEventForwarder(Config config, Client client, ObjectMapper objectMapper) {
        if (config.hasKey(Keys.EVENT_FORWARD_URL)) {
            String forwardType = config.getString(Keys.EVENT_FORWARD_TYPE);
            return switch (forwardType) {
                case "amqp" -> new EventForwarderAmqp(config, objectMapper);
                case "kafka" -> new EventForwarderKafka(config, objectMapper);
                case "mqtt" -> new EventForwarderMqtt(config, objectMapper);
                default -> new EventForwarderJson(config, client);
            };
        }
        return null;
    }

    @Singleton
    @Provides
    public static PositionForwarder providePositionForwarder(
            Config config, Client client, ExecutorService executorService,
            ObjectMapper objectMapper, CacheManager cacheManager) {
        if (config.hasKey(Keys.FORWARD_URL)) {
            return switch (config.getString(Keys.FORWARD_TYPE)) {
                case "json" -> new PositionForwarderJson(config, client, objectMapper, cacheManager);
                case "amqp" -> new PositionForwarderAmqp(config, objectMapper);
                case "kafka" -> new PositionForwarderKafka(config, objectMapper);
                case "mqtt" -> new PositionForwarderMqtt(config, objectMapper);
                case "redis" -> new PositionForwarderRedis(config, objectMapper);
                case "wialon" -> new PositionForwarderWialon(config, executorService, "1.0", false);
                default -> new PositionForwarderUrl(config, client, objectMapper);
            };
        }
        return null;
    }

    @Singleton
    @Provides
    public static VelocityEngine provideVelocityEngine(Config config) {
        Properties properties = new Properties();
        properties.setProperty("resource.loader.file.path", config.getString(Keys.TEMPLATES_ROOT) + "/");
        properties.setProperty("web.url", WebHelper.retrieveWebUrl(config));

        VelocityEngine velocityEngine = new VelocityEngine();
        velocityEngine.init(properties);
        return velocityEngine;
    }

    // OsrmClient binding
    @Singleton
    @Provides
    public static OsrmClient provideOsrmClient(Config config) {
        if ("osrm".equalsIgnoreCase(config.getString(Keys.ROUTING_TYPE))) {
            return new OsrmClient(config);
        }
        return null; // null = disabled, similar Geocoder/SpeedLimitProvider
    }

    // OsrmMatchClient — optional, enabled via routing.match.enabled=true
    // Requires OsrmClient as fallback; returns null if routing disabled or match not enabled
    @Singleton
    @Provides
    public static OsrmMatchClient provideOsrmMatchClient(
            Config config, @Nullable OsrmClient osrmClient) {
        if ("osrm".equalsIgnoreCase(config.getString(Keys.ROUTING_TYPE))
                && config.getBoolean(Keys.ROUTING_MATCH_ENABLED)) {
            if (osrmClient == null) {
                return null; // OsrmClient not enabled
            }
            return new OsrmMatchClient(config, osrmClient);
        }
        return null;
    }

}
