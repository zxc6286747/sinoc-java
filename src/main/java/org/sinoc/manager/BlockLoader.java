package org.sinoc.manager;


import org.sinoc.config.SystemProperties;
import org.sinoc.core.*;
import org.sinoc.db.DbFlushManager;
import org.sinoc.util.*;
import org.sinoc.validator.BlockHeaderValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

@Component
public class BlockLoader {
    private static final Logger logger = LoggerFactory.getLogger("blockqueue");

    @Autowired
    private BlockHeaderValidator headerValidator;

    @Autowired
    SystemProperties config;

    @Autowired
    private BlockchainImpl blockchain;

    @Autowired
    DbFlushManager dbFlushManager;

    Scanner scanner = null;

    DateTimeFormatter df = DateTimeFormatter.ofPattern("HH:mm:ss.SSSS");

    private void blockWork(Block block) {
        if (block.getNumber() >= blockchain.getBlockStore().getBestBlock().getNumber() || blockchain.getBlockStore().getBlockByHash(block.getHash()) == null) {

            if (block.getNumber() > 0 && !isValid(block.getHeader())) {
                throw new RuntimeException();
            }

            long s = System.currentTimeMillis();
            ImportResult result = blockchain.tryToConnect(block);

            if (block.getNumber() % 10 == 0) {
                System.out.println(LocalDateTime.now().format(df) + " Imported block " + block.getShortDescr() + ": " + result + " (prework: "
                        + exec1.getQueue().size() + ", work: " + exec2.getQueue().size() + ", blocks: " + exec1.getOrderMap().size() + ") in " +
                        (System.currentTimeMillis() - s) + " ms");
            }

        } else {

            if (block.getNumber() % 10000 == 0)
                System.out.println("Skipping block #" + block.getNumber());
        }
    }

    ExecutorPipeline<Block, Block> exec1;
    ExecutorPipeline<Block, ?> exec2;

    public void loadBlocks() {
        exec1 = new ExecutorPipeline(8, 1000, true, (Function<Block, Block>) b -> {
            if (b.getNumber() >= blockchain.getBlockStore().getBestBlock().getNumber()) {
                for (Transaction tx : b.getTransactionsList()) {
                    tx.getSender();
                }
            }
            return b;
            }, throwable -> logger.error("Unhandled exception: ", throwable)
        );

        exec2 = exec1.add(1, 1000, block -> {
            try {
                blockWork(block);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        String fileSrc = config.blocksLoader();
        try {
            final String blocksFormat = config.getConfig().hasPath("blocks.format") ? config.getConfig().getString("blocks.format") : null;
            System.out.println("Loading blocks: " + fileSrc + ", format: " + blocksFormat);

            if ("rlp".equalsIgnoreCase(blocksFormat)) {     // rlp encoded bytes
                Path path = Paths.get(fileSrc);
                // NOT OPTIMAL, but fine for tests
                byte[] data = Files.readAllBytes(path);
                RLPList list = RLP.decode2(data, 1);
                for (RLPElement item : list) {
                    Block block = new Block(item.getRLPData());
                    exec1.push(block);
                }
            } else {                                        // hex string
                FileInputStream inputStream = new FileInputStream(fileSrc);
                scanner = new Scanner(inputStream, "UTF-8");

                while (scanner.hasNextLine()) {

                    byte[] blockRLPBytes = Hex.decode(scanner.nextLine());
                    Block block = new Block(blockRLPBytes);

                    exec1.push(block);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }


        try {
            exec1.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        dbFlushManager.flushSync();

        System.out.println(" * Done * ");
        System.exit(0);
    }

    private boolean isValid(BlockHeader header) {
        return headerValidator.validateAndLog(header, logger);
    }
}
