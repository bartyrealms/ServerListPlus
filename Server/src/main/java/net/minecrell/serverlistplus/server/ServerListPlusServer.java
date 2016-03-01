package net.minecrell.serverlistplus.server;

import static com.google.common.base.Preconditions.checkState;
import static net.minecrell.serverlistplus.core.logging.Logger.ERROR;
import static net.minecrell.serverlistplus.core.logging.Logger.INFO;

import com.google.common.base.Splitter;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheBuilderSpec;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import net.minecrell.serverlistplus.core.ServerListPlusCore;
import net.minecrell.serverlistplus.core.config.PluginConf;
import net.minecrell.serverlistplus.core.config.storage.InstanceStorage;
import net.minecrell.serverlistplus.core.favicon.FaviconHelper;
import net.minecrell.serverlistplus.core.favicon.FaviconSource;
import net.minecrell.serverlistplus.core.logging.JavaServerListPlusLogger;
import net.minecrell.serverlistplus.core.logging.ServerListPlusLogger;
import net.minecrell.serverlistplus.core.plugin.ScheduledTask;
import net.minecrell.serverlistplus.core.plugin.ServerListPlusPlugin;
import net.minecrell.serverlistplus.core.plugin.ServerType;
import net.minecrell.serverlistplus.core.replacement.ReplacementManager;
import net.minecrell.serverlistplus.core.replacement.util.Literals;
import net.minecrell.serverlistplus.core.status.ResponseFetcher;
import net.minecrell.serverlistplus.core.status.StatusManager;
import net.minecrell.serverlistplus.core.status.StatusRequest;
import net.minecrell.serverlistplus.core.status.StatusResponse;
import net.minecrell.serverlistplus.core.util.Helper;
import net.minecrell.serverlistplus.core.util.Randoms;
import net.minecrell.serverlistplus.server.config.ServerConf;
import net.minecrell.serverlistplus.server.network.Netty;
import net.minecrell.serverlistplus.server.network.NetworkManager;
import net.minecrell.serverlistplus.server.status.Favicon;
import net.minecrell.serverlistplus.server.status.StatusClient;
import net.minecrell.serverlistplus.server.status.StatusPingResponse;
import net.minecrell.serverlistplus.server.status.UserProfile;
import net.minecrell.serverlistplus.server.util.FormattingCodes;
import net.minecrell.serverlistplus.server.util.RunnableTimerTask;

import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class ServerListPlusServer implements ServerListPlusPlugin {

    private static ServerListPlusServer instance;

    private final ServerListPlusCore core;
    private final Logger logger;
    private final Path workingDir;

    private final NetworkManager network;

    private final Timer timer = new Timer(true);

    private boolean started;

    private boolean playerTracking;
    private ImmutableList<String> loginMessages;

    // Favicon cache
    private final CacheLoader<FaviconSource, Optional<String>> faviconLoader =
            new CacheLoader<FaviconSource, Optional<String>>() {
                @Override
                public Optional<String> load(FaviconSource source) throws Exception {
                    // Try loading the favicon
                    BufferedImage image = FaviconHelper.loadSafely(core, source);
                    if (image == null) return Optional.empty(); // Favicon loading failed
                    else return Optional.of(Favicon.create(image));
                }
            };
    private LoadingCache<FaviconSource, Optional<String>> faviconCache;

    public ServerListPlusServer(Logger logger) throws UnknownHostException {
        checkState(instance == null, "Server was already initialized");
        instance = this;

        this.logger = logger;
        this.workingDir = Paths.get("");

        logger.log(INFO, "Loading...");
        this.core = new ServerListPlusCore(this, new ServerProfileManager());

        ServerConf conf = this.core.getConf(ServerConf.class);
        this.network = new NetworkManager(this, Netty.parseAddress(conf.Address));
        logger.log(INFO, "Successfully loaded!");
    }

    public boolean start() {
        this.started = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        try {
            this.network.start();
        } catch (Exception e) {
            this.logger.log(ERROR, "Failed to start network manager", e);
            this.stop();
            return false;
        }

        return true;
    }

    public void join() throws InterruptedException {
        this.network.join();
    }

    public boolean stop() {
        if (this.started) {
            this.logger.info("Stopping...");

            try {
                this.network.stop();
            } catch (Exception e) {
                this.logger.log(ERROR, "Failed to stop network manager", e);
                return false;
            }

            this.core.stop();

            this.started = false;
            return true;
        }

        return false;
    }

    public static StatusPingResponse postLegacy(InetSocketAddress address, InetSocketAddress virtualHost) {
        StatusPingResponse response = instance.handle(new StatusClient(address, OptionalInt.empty(), virtualHost));
        response.getVersion().setProtocol(Byte.MAX_VALUE);
        if (response.getPlayers() == null) {
            response.setPlayers(new StatusPingResponse.Players(0, -1, null));
        }
        return response;
    }

    public static StatusPingResponse post(StatusClient client) {
        return instance.handle(client);
    }

    public static String postLogin(StatusClient client, String name) {
        return instance.handleLogin(client, name);
    }

    public String handleLogin(StatusClient client, String name) {
        if (this.playerTracking) {
            core.updateClient(client.getAddress().getAddress(), null, name);
        }

        String message = Randoms.nextEntry(this.loginMessages);
        return Literals.replace(message, "%player%", name);
    }

    public StatusPingResponse handle(StatusClient client) {
        StatusPingResponse ping = new StatusPingResponse();

        StatusRequest request = core.createRequest(client.getAddress().getAddress());
        client.getProtocol().ifPresent(request::setProtocolVersion);

        InetSocketAddress host = client.getVirtualHost();
        if (host != null) {
            request.setTarget(host);
        }

        final StatusPingResponse.Players players = ping.getPlayers();
        final StatusPingResponse.Version version = ping.getVersion();

        StatusResponse response = request.createResponse(core.getStatus(),
                // Return unknown player counts if it has been hidden
                new ResponseFetcher() {
                    @Override
                    public Integer getOnlinePlayers() {
                        return players != null ? players.getOnline() : null;
                    }

                    @Override
                    public Integer getMaxPlayers() {
                        return players != null ? players.getMax() : null;
                    }

                    @Override
                    public int getProtocolVersion() {
                        return version != null ? version.getProtocol() : 0;
                    }
                });

        // Description
        String message = response.getDescription();
        if (message != null) ping.setDescription(message);

        if (version != null) {
            // Version name
            message = response.getVersion();
            if (message != null) version.setName(message);
            // Protocol version
            Integer protocol = response.getProtocolVersion();
            if (protocol != null) version.setProtocol(protocol);
        }

        // Favicon
        FaviconSource favicon = response.getFavicon();
        if (favicon != null) {
            Optional<String> icon = faviconCache.getUnchecked(favicon);
            if (icon.isPresent()) ping.setFavicon(icon.get());
        }

        if (response.hidePlayers()) {
            ping.setPlayers(null);
        } else {
            StatusPingResponse.Players newPlayers = players;
            if (newPlayers == null) {
                newPlayers = new StatusPingResponse.Players(0, 0, null);
                ping.setPlayers(newPlayers);
            }

            // Online players
            Integer count = response.getOnlinePlayers();
            if (count != null) newPlayers.setOnline(count);
            // Max players
            count = response.getMaxPlayers();
            if (count != null) newPlayers.setMax(count);

            // Player hover
            message = response.getPlayerHover();
            if (message != null) {
                if (response.useMultipleSamples()) {
                    count = response.getDynamicSamples();
                    List<String> lines = count != null ? Helper.splitLinesCached(message, count) :
                            Helper.splitLinesCached(message);

                    UserProfile[] sample = new UserProfile[lines.size()];
                    for (int i = 0; i < sample.length; i++)
                        sample[i] = new UserProfile(lines.get(i), StatusManager.EMPTY_UUID);

                    newPlayers.setSample(sample);
                } else
                    newPlayers.setSample(new UserProfile[]{
                            new UserProfile(message, StatusManager.EMPTY_UUID) });
            }
        }

        return ping;
    }

    private static final Splitter COMMAND_SPLITTER = Splitter.on(' ').trimResults().omitEmptyStrings();

    public boolean processCommand(String command) {
        if (command.equals("stop")) {
            return stop();
        }

        List<String> args = COMMAND_SPLITTER.splitToList(command);
        this.core.executeCommand(ConsoleCommandSender.INSTANCE, "serverlistplus", args.toArray(new String[args.size()]));
        return false;
    }

    @Override
    public ServerListPlusCore getCore() {
        return this.core;
    }

    @Override
    public ServerType getServerType() {
        return ServerType.SERVER;
    }

    @Override
    public String getServerImplementation() {
        return "ServerListPlusServer";
    }

    @Override
    public Path getPluginFolder() {
        return this.workingDir;
    }

    @Override
    public Integer getOnlinePlayers(String location) {
        return null;
    }

    @Override
    public Iterator<String> getRandomPlayers() {
        return null;
    }

    @Override
    public Iterator<String> getRandomPlayers(String location) {
        return null;
    }

    @Override
    public Cache<?, ?> getRequestCache() {
        return null;
    }

    @Override
    public LoadingCache<FaviconSource, Optional<String>> getFaviconCache() {
        return this.faviconCache;
    }

    @Override
    public void runAsync(Runnable task) {
        this.timer.schedule(new RunnableTimerTask(task), 0); // TODO: Remove
    }

    @Override
    public ScheduledTask scheduleAsync(Runnable task, long repeat, TimeUnit unit) {
        TimerTask timerTask = new RunnableTimerTask(task);
        this.timer.scheduleAtFixedRate(timerTask, 0, unit.toMillis(repeat));
        return new ScheduledTimerTask(timerTask);
    }

    @Override
    public String colorize(String s) {
        return FormattingCodes.colorize(s);
    }

    @Override
    public ServerListPlusLogger createLogger(ServerListPlusCore core) {
        return new JavaServerListPlusLogger(this.core, this.logger);
    }

    @Override
    public void initialize(ServerListPlusCore core) {
        core.registerConf(ServerConf.class, new ServerConf(), ServerConf.getExample(), "Server");
    }

    @Override
    public void reloadCaches(ServerListPlusCore core) {

    }

    @Override
    public void reloadFaviconCache(CacheBuilderSpec spec) {
        if (spec != null) {
            this.faviconCache = CacheBuilder.from(spec).build(faviconLoader);
        } else {
            // Delete favicon cache
            faviconCache.invalidateAll();
            faviconCache.cleanUp();
            this.faviconCache = null;
        }
    }

    @Override
    public void configChanged(ServerListPlusCore core, InstanceStorage<Object> confs) {
        this.playerTracking = confs.get(PluginConf.class).PlayerTracking.Enabled;

        ServerConf conf = confs.get(ServerConf.class);

        ImmutableList.Builder<String> builder = ImmutableList.builder();
        for (String message : conf.Login.Message) {
            builder.add(ReplacementManager.replaceStatic(core, message));
        }

        this.loginMessages = builder.build();
    }

    @Override
    public void statusChanged(StatusManager status, boolean hasChanges) {

    }

}