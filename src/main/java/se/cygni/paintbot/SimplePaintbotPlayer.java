package se.cygni.paintbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.WebSocketSession;
import se.cygni.paintbot.api.event.*;
import se.cygni.paintbot.api.exception.InvalidPlayerName;
import se.cygni.paintbot.api.model.CharacterAction;
import se.cygni.paintbot.api.model.GameMode;
import se.cygni.paintbot.api.model.GameSettings;
import se.cygni.paintbot.api.model.PlayerPoints;
import se.cygni.paintbot.api.response.PlayerRegistered;
import se.cygni.paintbot.api.util.GameSettingsUtils;
// import se.cygni.paintbot.client.*;

import se.cygni.paintbot.client.AnsiPrinter;
import se.cygni.paintbot.client.BasePaintbotClient;
import se.cygni.paintbot.client.MapUtility;
import se.cygni.paintbot.client.MapCoordinate;
import se.cygni.paintbot.client.MapUtilityImpl;
import java.util.stream.Collectors;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// import javax.swing.colorchooser.ColorChooserComponentFactory;

public class SimplePaintbotPlayer extends BasePaintbotClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimplePaintbotPlayer.class);

    // Set to false if you want to start the game from a GUI
    private static final boolean AUTO_START_GAME = true;

    // Personalise your game ...
    private static final String SERVER_NAME = "server.paintbot.cygni.se";
    private static final int SERVER_PORT = 80;

    private static final GameMode GAME_MODE = GameMode.TRAINING;
    private static final String BOT_NAME = "The Simple Painter " + (int) (Math.random() * 1000) ;

    // Set to false if you don't want the game world printed every game tick.
    private static final boolean ANSI_PRINTER_ACTIVE = false;
    private AnsiPrinter ansiPrinter = new AnsiPrinter(ANSI_PRINTER_ACTIVE, true);

    private long lastGameTickExplosion = 0;

    public static void main(String[] args) {
        SimplePaintbotPlayer simplePaintbotPlayer = new SimplePaintbotPlayer();

        try {
            ListenableFuture<WebSocketSession> connect = simplePaintbotPlayer.connect();
            connect.get();
        } catch (Exception e) {
            LOGGER.error("Failed to connect to server", e);
            System.exit(1);
        }

        startTheBot(simplePaintbotPlayer);
    }

    /**
     * The Paintbot client will continue to run ...
     * : in TRAINING mode, until the single game ends.
     * : in TOURNAMENT mode, until the server tells us its all over.
     */
    private static void startTheBot(final SimplePaintbotPlayer simplePaintbotPlayer) {
        Runnable task = () -> {
            do {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } while (simplePaintbotPlayer.isPlaying());

            LOGGER.info("Shutting down");
        };

        Thread thread = new Thread(task);
        thread.start();
    }

    /*
     * If recently explode powerup -> pause explode for some ticks 
     *  
     * 
     * 
     */


    @Override
    public void onMapUpdate(MapUpdateEvent mapUpdateEvent) {
        // Do your implementation here! (or at least start from here, entry point for updates)
        ansiPrinter.printMap(mapUpdateEvent);

        // MapUtil contains lot's of useful methods for querying the map!
        MapUtility mapUtil = new MapUtilityImpl(mapUpdateEvent.getMap(), getPlayerId());
        
        // Check if we're carrying a power up, if we do -> EXPLODE
        if (mapUtil.getMyCharacterInfo().isCarryingPowerUp()) {
            
            // Check if we recently exploded
            if ((mapUpdateEvent.getGameTick()-lastGameTickExplosion) > 7) {
                lastGameTickExplosion = mapUpdateEvent.getGameTick();
                registerMove(mapUpdateEvent.getGameTick(), CharacterAction.EXPLODE);
                return;
            }
        } 
        
        // Init chosenAction
        CharacterAction chosenAction = CharacterAction.STAY;

        // Create a list of possible actions
        List<CharacterAction> possibleActions = new ArrayList<>();

        // Let's see in which movement actions I can take
        for (CharacterAction action : CharacterAction.values()) {
            if (mapUtil.canIMoveInDirection(action)) {
                possibleActions.add(action);
            }
        }
        
        // Getting the closest powerup
        MapCoordinate[] powerUpsCoords = mapUtil.getCoordinatesContainingPowerUps();
        MapCoordinate playerCoord = mapUtil.getMyCoordinate();
        MapCoordinate closestCoordPowerUp = null;
        int closestManhattanPowerUp = Integer.MAX_VALUE;
        
        // Calculate where the closest Power Up is
        if (powerUpsCoords != null) { 
            for (MapCoordinate coord : powerUpsCoords) {
                if (closestCoordPowerUp == null) {
                    closestCoordPowerUp = coord;
                    closestManhattanPowerUp = playerCoord.getManhattanDistanceTo(coord);
                }
                else {
                    if (playerCoord.getManhattanDistanceTo(coord) < closestManhattanPowerUp) {
                        closestCoordPowerUp = coord;
                        closestManhattanPowerUp = playerCoord.getManhattanDistanceTo(coord);
                    }
                }
            }
        }

        // If we find a power up, calculate which direction to take
        if (closestCoordPowerUp != null) {
            List<CharacterAction> actionsPowerUp = new ArrayList<>();
            if (playerCoord.x < closestCoordPowerUp.x) {
                actionsPowerUp.add(CharacterAction.RIGHT);
            } else if (playerCoord.x > closestCoordPowerUp.x) {
                actionsPowerUp.add(CharacterAction.LEFT);
            }
            if (playerCoord.y < closestCoordPowerUp.y) {
                actionsPowerUp.add(CharacterAction.DOWN);
            } else if (playerCoord.y > closestCoordPowerUp.y) {
                actionsPowerUp.add(CharacterAction.UP);
            }

            // Filter out invalid moves, like going into obstacles 
            List<CharacterAction> validActionsPowerUp = actionsPowerUp.stream()
                                        .filter(mapUtil::canIMoveInDirection)
                                        .collect(Collectors.toList());
            
            // Move towards the power up if possible
            if (!validActionsPowerUp.isEmpty()) {
                Random rand = new Random();
                chosenAction = validActionsPowerUp.get(rand.nextInt(validActionsPowerUp.size()));
                registerMove(mapUpdateEvent.getGameTick(), chosenAction);
                return;
            } 

            // If it's not, there is an obstacle in front of us, then go around it
            else {
                if (actionsPowerUp.contains(CharacterAction.RIGHT) || actionsPowerUp.contains(CharacterAction.LEFT)) {
                    validActionsPowerUp.add(CharacterAction.UP);
                    validActionsPowerUp.add(CharacterAction.DOWN);
                    validActionsPowerUp.retainAll(possibleActions);
                    if (!validActionsPowerUp.isEmpty()) {
                        Random rand = new Random();
                        chosenAction = validActionsPowerUp.get(rand.nextInt(validActionsPowerUp.size()));
                        registerMove(mapUpdateEvent.getGameTick(), chosenAction);
                        return;
                    }
                } else if (actionsPowerUp.contains(CharacterAction.UP) || actionsPowerUp.contains(CharacterAction.DOWN)) {
                    validActionsPowerUp.add(CharacterAction.RIGHT);
                    validActionsPowerUp.add(CharacterAction.LEFT);
                    validActionsPowerUp.retainAll(possibleActions);
                    if (!validActionsPowerUp.isEmpty()) {
                        Random rand = new Random();
                        chosenAction = validActionsPowerUp.get(rand.nextInt(validActionsPowerUp.size()));
                        registerMove(mapUpdateEvent.getGameTick(), chosenAction);
                        return;
                    }
                }
            }    
            
            /*
            actionsPowerUp.retainAll(possibleActions);

            if (!actionsPowerUp.isEmpty()) {
                Random rand = new Random();
                chosenAction = actionsPowerUp.get(rand.nextInt(actionsPowerUp.size()));
                registerMove(mapUpdateEvent.getGameTick(), chosenAction);
                return;
            }*/
        }
        
        MapCoordinate[] visitedCoords = mapUtil.getPlayerColouredCoordinates(getPlayerId());

        // Choose a random direction
        Random rand = new Random();
        if (!possibleActions.isEmpty()) {
            chosenAction = possibleActions.get(rand.nextInt(possibleActions.size()));
        }

        // Register action here!
        registerMove(mapUpdateEvent.getGameTick(), chosenAction);
    }

    @Override
    public void onPaintbotDead(CharacterStunnedEvent characterStunnedEvent) {
        // Wrong name, does not die. Might be stunned though by crashing or getting caught in explosion
    }


    @Override
    public void onInvalidPlayerName(InvalidPlayerName invalidPlayerName) {
        LOGGER.debug("InvalidPlayerNameEvent: " + invalidPlayerName);
    }

    @Override
    public void onGameResult(GameResultEvent gameResultEvent) {
        LOGGER.info("Game result:");
        gameResultEvent.getPlayerRanks().forEach(playerRank -> LOGGER.info(playerRank.toString()));
    }

    @Override
    public void onGameEnded(GameEndedEvent gameEndedEvent) {
        LOGGER.debug("GameEndedEvent: " + gameEndedEvent);
    }

    @Override
    public void onGameStarting(GameStartingEvent gameStartingEvent) {
        LOGGER.debug("GameStartingEvent: " + gameStartingEvent);
    }

    @Override
    public void onPlayerRegistered(PlayerRegistered playerRegistered) {
        LOGGER.info("PlayerRegistered: " + playerRegistered);

        if (AUTO_START_GAME) {
            startGame();
        }
    }

    @Override
    public void onTournamentEnded(TournamentEndedEvent tournamentEndedEvent) {
        LOGGER.info("Tournament has ended, winner playerId: {}", tournamentEndedEvent.getPlayerWinnerId());
        int c = 1;
        for (PlayerPoints pp : tournamentEndedEvent.getGameResult()) {
            LOGGER.info("{}. {} - {} points", c++, pp.getName(), pp.getPoints());
        }
    }

    @Override
    public void onGameLink(GameLinkEvent gameLinkEvent) {
        LOGGER.info("The game can be viewed at: {}", gameLinkEvent.getUrl());
    }

    @Override
    public void onSessionClosed() {
        LOGGER.info("Session closed");
    }

    @Override
    public void onConnected() {
        LOGGER.info("Connected, registering for training...");
        GameSettings gameSettings = GameSettingsUtils.trainingWorld();
        registerForGame(gameSettings);
    }

    @Override
    public String getName() {
        return BOT_NAME;
    }

    @Override
    public String getServerHost() {
        return SERVER_NAME;
    }

    @Override
    public int getServerPort() {
        return SERVER_PORT;
    }

    @Override
    public GameMode getGameMode() {
        return GAME_MODE;
    }
}
