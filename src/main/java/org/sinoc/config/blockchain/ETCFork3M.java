package org.sinoc.config.blockchain;

import static org.sinoc.util.BIUtil.max;

import java.math.BigInteger;

import org.sinoc.config.BlockchainConfig;
import org.sinoc.core.BlockHeader;

/**
 * Ethereum Classic HF on Block #3_000_000:
 * - EXP reprice (EIP-160)
 * - Replay Protection (EIP-155) (chainID: 61)
 * - Difficulty Bomb delay (ECIP-1010) (https://github.com/ethereumproject/ECIPs/blob/master/ECIPs/ECIP-1010.md)
 *
 */
public class ETCFork3M extends Eip160HFConfig {
    public ETCFork3M(BlockchainConfig parent) {
        super(parent);
    }

    @Override
    public Integer getChainId() {
        return 61;
    }

    @Override
    public boolean eip161() {
        return false;
    }

    @Override
    public BigInteger calcDifficulty(BlockHeader curBlock, BlockHeader parent) {
        BigInteger pd = parent.getDifficultyBI();
        BigInteger quotient = pd.divide(getConstants().getDIFFICULTY_BOUND_DIVISOR());

        BigInteger sign = getCalcDifficultyMultiplier(curBlock, parent);

        BigInteger fromParent = pd.add(quotient.multiply(sign));
        BigInteger difficulty = max(getConstants().getMINIMUM_DIFFICULTY(), fromParent);

        int explosion = getExplosion(curBlock, parent);

        if (explosion >= 0) {
            difficulty = max(getConstants().getMINIMUM_DIFFICULTY(), difficulty.add(BigInteger.ONE.shiftLeft(explosion)));
        }

        return difficulty;
    }

    @Override
    public BigInteger getCalcDifficultyMultiplier(BlockHeader curBlock, BlockHeader parent) {
        return BigInteger.valueOf(Math.max(1 - (curBlock.getTimestamp() - parent.getTimestamp()) / 10, -99));
    }


    protected int getExplosion(BlockHeader curBlock, BlockHeader parent) {
        int pauseBlock = 3000000;
        int contBlock = 5000000;
        int delay = (contBlock - pauseBlock) / 100000;
        int fixedDiff = (pauseBlock / 100000) - 2;

        if (curBlock.getNumber() < contBlock) {
            return fixedDiff;
        } else {
            return (int) ((curBlock.getNumber() / 100000) - delay - 2);
        }
    }
}
