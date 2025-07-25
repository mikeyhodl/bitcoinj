/*
 * Copyright 2012 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.wallettool;

import com.google.protobuf.ByteString;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.Network;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.base.internal.ByteUtils;
import org.bitcoinj.base.internal.TimeUtils;
import org.bitcoinj.core.AbstractBlockChain;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.protobuf.wallet.Protos;
import org.bitcoinj.script.ScriptException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.CoinSelector;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChainGroupStructure;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.Wallet.BalanceType;
import org.bitcoinj.wallet.WalletProtobufSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.bitcoinj.base.Coin.parseCoin;

/**
 * A command line tool for manipulating wallets and working with Bitcoin.
 */
@CommandLine.Command(name = "wallet-tool", usageHelpAutoWidth = true, sortOptions = false, description = "Print and manipulate wallets.")
public class WalletTool implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Action to perform. Valid values:%n" +
            "  dump                 Loads and prints the given wallet in textual form to stdout. Private keys and seed are only printed if --dump-privkeys is specified. If the wallet is encrypted, also specify the --password option to dump the private keys and seed.%n" +
            "                       If --dump-lookahead is present, also show pregenerated but not yet issued keys.%n" +
            "  raw-dump             Prints the wallet as a raw protobuf with no parsing or sanity checking applied.%n" +
            "  create               Makes a new wallet in the file specified by --wallet. Will complain and require --force if the wallet already exists.%n" +
            "                       If --seed is present, it should specify either a mnemonic code or hex/base58 raw seed bytes.%n" +
            "                       If --watchkey is present, it creates a watching wallet using the specified base58 xpub.%n" +
            "                       If --seed or --watchkey is combined with either --date or --unixtime, use that as a birthdate for the wallet. See the set-creation-time action for the meaning of these flags.%n" +
            "                       If --output-script-type is present, use that for deriving addresses.%n" +
            "  add-key              Adds a new key to the wallet.%n" +
            "                       If --date is specified, that's the creation date.%n" +
            "                       If --unixtime is specified, that's the creation time and it overrides --date.%n" +
            "                       If --privkey is specified, use as a WIF-, hex- or base58-encoded private key.%n" +
            "                       Don't specify --pubkey in that case, it will be derived automatically.%n" +
            "                       If --pubkey is specified, use as a hex/base58 encoded non-compressed public key.%n" +
            "  add-addr             Requires --addr to be specified, and adds it as a watching address.%n" +
            "  delete-key           Removes the key specified by --pubkey or --addr from the wallet.%n" +
            "  current-receive-addr Prints the current receive address, deriving one if needed. Addresses derived with this action are%n" +
            "                       independent of addresses derived with the add-key action.%n" +
            "  sync                 Sync the wallet with the latest block chain (download new transactions).%n" +
            "                       If the chain file does not exist or if --force is present, this will RESET the wallet.%n" +
            "  reset                Deletes all transactions from the wallet, for if you want to replay the chain.%n" +
            "  send                 Creates and broadcasts a transaction from the given wallet.%n" +
            "                       Requires either --output or --payment-request to be specified.%n" +
            "                       If --output is specified, a transaction is created from the provided output from this wallet and broadcasted, e.g.:%n" +
            "                         --output=1GthXFQMktFLWdh5EPNGqbq3H6WdG8zsWj:1.245%n" +
            "                       You can repeat --output=address:value multiple times.%n" +
            "                       There is a magic value ALL which empties the wallet to that address, e.g.:%n" +
            "                         --output=1GthXFQMktFLWdh5EPNGqbq3H6WdG8zsWj:ALL%n" +
            "                       The output destination can also be a native segwit address.%n" +
            "                       If the output destination starts with 04 and is 65 or 33 bytes long it will be treated as a public key instead of an address and the send will use%n" +
            "                       <key> CHECKSIG as the script.%n" +
            "                       Other options include:%n" +
            "                         --fee-per-vkb or --fee-sat-per-vbyte sets the network fee, see below%n" +
            "                         --select-addr or --select-output to select specific outputs%n" +
            "                         --locktime=1234  sets the lock time to block 1234%n" +
            "                         --locktime=2013/01/01  sets the lock time to 1st Jan 2013%n" +
            "                         --allow-unconfirmed will let you create spends of pending non-change outputs.%n" +
            "                         --no-pki disables pki verification for payment requests.%n" +
            "  encrypt              Requires --password and uses it to encrypt the wallet in place.%n" +
            "  decrypt              Requires --password and uses it to decrypt the wallet in place.%n" +
            "  upgrade              Upgrade deterministic wallets to the given script type.%n" +
            "                       If --output-script-type is present, use that as the upgrade target.%n" +
            "  rotate               Takes --date and sets that as the key rotation time. Any coins controlled by keys or HD chains created before this date will be re-spent to a key (from an HD tree) that was created after it.%n" +
            "                       If --date is missing, the current time is assumed. If the time covers all keys, a new HD tree%n" +
            "                       will be created from a new random seed.%n" +
            "  set-creation-time    Modify the creation time of the active chains of this wallet. This is useful for repairing wallets that accidently have been created \"in the future\". Currently, watching wallets are not supported.%n" +
            "                       If --date is specified, that's the creation date.%n" +
            "                       If --unixtime is specified, that's the creation time and it overrides --date.%n" +
            "                       If you omit both options, the creation time is being cleared (set to 0).%n")
    private String actionStr;
    @CommandLine.Option(names = "--net", description = "Which network to connect to. Valid values: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}")
    private BitcoinNetwork net = BitcoinNetwork.MAINNET;
    @CommandLine.Option(names = "--debuglog", description = "Enables logging from the core library.")
    private boolean debugLog = false;
    @CommandLine.Option(names = "--force", description = "Overrides any safety checks on the requested action.")
    private boolean force = false;
    @CommandLine.Option(names = "--wallet", description = "Specifies what wallet file to load and save.")
    private File walletFile = null;
    @CommandLine.Option(names = "--seed", description = "Specifies either a mnemonic code or hex/base58 raw seed bytes.")
    private String seedStr = null;
    @CommandLine.Option(names = "--watchkey", description = "Describes a watching wallet using the specified base58 xpub.")
    private String watchKeyStr = null;
    @CommandLine.Option(names = "--output-script-type", description = "Provide an output script type to any action that requires one. Valid values: P2PKH, P2WPKH. Default: ${DEFAULT-VALUE}")
    private ScriptType outputScriptType = ScriptType.P2PKH;
    @CommandLine.Option(names = "--date", description = "Provide a date in form YYYY-MM-DD to any action that requires one.")
    private LocalDate date = null;
    @CommandLine.Option(names = "--unixtime", description = "Provide a date in seconds since epoch.")
    private Long unixtime = null;
    @CommandLine.Option(names = "--waitfor", description = "You can wait for the condition specified by the --waitfor flag to become true. Transactions and new blocks will be processed during this time. When the waited for condition is met, the tx/block hash will be printed. Waiting occurs after the --action is performed, if any is specified. Valid values:%n" +
            "EVER       Never quit.%n" +
            "WALLET_TX  Any transaction that sends coins to or from the wallet.%n" +
            "BLOCK      A new block that builds on the best chain.%n" +
            "BALANCE    Waits until the wallets balance meets the --condition.")
    private WaitForEnum waitFor = null;
    @CommandLine.Option(names = "--filter", description = "Use filter when synching the chain. Valid values: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}")
    private Filter filter = Filter.SERVER;
    @CommandLine.Option(names = "--chain", description = "Specifies the name of the file that stores the block chain.")
    private File chainFile = null;
    @CommandLine.Option(names = "--pubkey", description = "Specifies a hex/base58 encoded non-compressed public key.")
    private String pubKeyStr;
    @CommandLine.Option(names = "--privkey", description = "Specifies a WIF-, hex- or base58-encoded private key.")
    private String privKeyStr;
    @CommandLine.Option(names = "--addr", description = "Specifies a Bitcoin address, either segwit or legacy.")
    private String addrStr;
    @CommandLine.Option(names = "--peers", description = "Comma separated IP addresses/domain names for connections instead of peer discovery.")
    private String peersStr;
    @CommandLine.Option(names = "--xpubkeys", description = "Specifies external public keys.")
    private String xpubKeysStr;
    @CommandLine.Option(names = "--select-addr", description = "When sending, only pick coins from this address.")
    private String selectAddrStr;
    @CommandLine.Option(names = "--select-output", description = "When sending, only pick coins from this output.")
    private String selectOutputStr;
    @CommandLine.Option(names = "--output", description = "Creates an output with the specified amount, separated by a colon. The special amount ALL is used to use the entire balance.")
    private List<String> outputsStr;
    @CommandLine.Option(names = "--fee-per-vkb", description = "Sets the network fee in Bitcoin per kilobyte when sending, e.g. --fee-per-vkb=0.0005")
    private String feePerVkbStr;
    @CommandLine.Option(names = "--fee-sat-per-vbyte", description = "Sets the network fee in satoshi per byte when sending, e.g. --fee-sat-per-vbyte=50")
    private String feeSatPerVbyteStr;
    @CommandLine.Option(names = "--condition", description = "Allows you to specify a numeric condition for other commands. The format is one of the following operators = < > <= >= immediately followed by a number.%nExamples: --condition=\">5.10\" or --condition=\"<=1\"")
    private String conditionStr = null;
    @CommandLine.Option(names = "--locktime", description = "Specifies a lock-time either by date or by block number.")
    private String lockTimeStr;
    @CommandLine.Option(names = "--allow-unconfirmed", description = "Lets you create spends of pending non-change outputs.")
    private boolean allowUnconfirmed = false;
    @CommandLine.Option(names = "--offline", description = "If specified when sending, don't try and connect, just write the tx to the wallet.")
    private boolean offline = false;
    @CommandLine.Option(names = "--ignore-mandatory-extensions", description = "If a wallet has unknown required extensions that would otherwise cause load failures, this overrides that.")
    private boolean ignoreMandatoryExtensions = false;
    @CommandLine.Option(names = "--password", description = "For an encrypted wallet, the password is provided here.")
    private String password = null;
    @CommandLine.Option(names = "--no-pki", description = "Disables pki verification for payment requests.")
    private boolean noPki = false;
    @CommandLine.Option(names = "--dump-privkeys", description = "Private keys and seed are printed.")
    private boolean dumpPrivKeys = false;
    @CommandLine.Option(names = "--dump-lookahead", description = "Show pregenerated but not yet issued keys.")
    private boolean dumpLookAhead = false;
    @CommandLine.Option(names = "--help", usageHelp = true, description = "Displays program options.")
    private boolean help;

    private static final Logger log = LoggerFactory.getLogger(WalletTool.class);

    private static NetworkParameters params;
    private static BlockStore store;
    private static AbstractBlockChain chain;
    private static PeerGroup peerGroup;
    private static Wallet wallet;

    public static class Condition {
        public enum Type {
            // Less than, greater than, less than or equal, greater than or equal.
            EQUAL, LT, GT, LTE, GTE
        }

        Type type;
        String value;

        public Condition(String from) {
            if (from.length() < 2) throw new RuntimeException("Condition string too short: " + from);

            if (from.startsWith("<=")) type = Type.LTE;
            else if (from.startsWith(">=")) type = Type.GTE;
            else if (from.startsWith("<")) type = Type.LT;
            else if (from.startsWith("=")) type = Type.EQUAL;
            else if (from.startsWith(">")) type = Type.GT;
            else throw new RuntimeException("Unknown operator in condition: " + from);

            String s;
            switch (type) {
                case LT:
                case GT:
                case EQUAL:
                    s = from.substring(1);
                    break;
                case LTE:
                case GTE:
                    s = from.substring(2);
                    break;
                default:
                    throw new RuntimeException("Unreachable");
            }
            value = s;
        }

        public boolean matchBitcoins(Coin comparison) {
            try {
                Coin units = parseCoin(value);
                switch (type) {
                    case LT: return comparison.compareTo(units) < 0;
                    case GT: return comparison.compareTo(units) > 0;
                    case EQUAL: return comparison.compareTo(units) == 0;
                    case LTE: return comparison.compareTo(units) <= 0;
                    case GTE: return comparison.compareTo(units) >= 0;
                    default:
                        throw new RuntimeException("Unreachable");
                }
            } catch (NumberFormatException e) {
                System.err.println("Could not parse value from condition string: " + value);
                System.exit(1);
                return false;
            }
        }
    }

    private static Condition condition;

    public enum ActionEnum {
        DUMP,
        RAW_DUMP,
        CREATE,
        ADD_KEY,
        ADD_ADDR,
        DELETE_KEY,
        CURRENT_RECEIVE_ADDR,
        SYNC,
        RESET,
        SEND,
        ENCRYPT,
        DECRYPT,
        UPGRADE,
        ROTATE,
        SET_CREATION_TIME,
    }

    public enum WaitForEnum {
        EVER,
        WALLET_TX,
        BLOCK,
        BALANCE
    }

    public enum Filter {
        NONE,
        SERVER, // bloom filter
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new WalletTool()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws IOException, BlockStoreException {
        ActionEnum action;
        try {
            action = ActionEnum.valueOf(actionStr.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            System.err.println("Could not understand action name " + actionStr);
            return 1;
        }

        if (debugLog) {
            BriefLogFormatter.init();
            log.info("Starting up ...");
        } else {
            // Disable logspam unless there is a flag.
            java.util.logging.Logger logger = LogManager.getLogManager().getLogger("");
            logger.setLevel(Level.SEVERE);
        }
        params = NetworkParameters.of(net);
        String fileName = String.format("%s.chain", net);
        if (chainFile == null) {
            chainFile = new File(fileName);
        }
        Context.propagate(new Context());

        if (conditionStr != null) {
            condition = new Condition(conditionStr);
        }

        if (action == ActionEnum.CREATE) {
            createWallet(net, walletFile);
            return 0;  // We're done.
        }
        if (!walletFile.exists()) {
            System.err.println("Specified wallet file " + walletFile + " does not exist. Try wallet-tool --wallet=" + walletFile + " create");
            return 1;
        }

        if (action == ActionEnum.RAW_DUMP) {
            // Just parse the protobuf and print, then bail out. Don't try and do a real deserialization. This is
            // useful mostly for investigating corrupted wallets.
            try (FileInputStream stream = new FileInputStream(walletFile)) {
                Protos.Wallet proto = WalletProtobufSerializer.parseToProto(stream);
                proto = attemptHexConversion(proto);
                System.out.println(proto.toString());
                return 0;
            }
        }

        boolean forceReset = action == ActionEnum.RESET
                || (action == ActionEnum.SYNC
                && force);
        try {
            wallet = Wallet.loadFromFile(walletFile, WalletProtobufSerializer.WalletFactory.DEFAULT, forceReset, ignoreMandatoryExtensions);
        } catch (UnreadableWalletException e) {
            System.err.println("Failed to load wallet '" + walletFile + "': " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
        if (wallet.network() != net) {
            System.err.println("Wallet does not match requested network: " +
                    wallet.network() + " vs " + net);
            return 1;
        }

        // What should we do?
        switch (action) {
            case DUMP: dumpWallet(); break;
            case ADD_KEY: addKey(); break;
            case ADD_ADDR: addAddr(); break;
            case DELETE_KEY: deleteKey(); break;
            case CURRENT_RECEIVE_ADDR: currentReceiveAddr(); break;
            case RESET: reset(); break;
            case SYNC: syncChain(); break;
            case SEND:
                if (feePerVkbStr != null && feeSatPerVbyteStr != null) {
                    System.err.println("--fee-per-kb and --fee-sat-per-byte cannot be used together.");
                    return 1;
                } else if (outputsStr != null) {
                    Coin feePerVkb;
                    if (feePerVkbStr != null)
                        feePerVkb = parseCoin(feePerVkbStr);
                    else if (feeSatPerVbyteStr != null)
                        feePerVkb = Coin.valueOf(Long.parseLong(feeSatPerVbyteStr) * 1000);
                    else
                        feePerVkb = null;
                    if (selectAddrStr != null && selectOutputStr != null) {
                        System.err.println("--select-addr and --select-output cannot be used together.");
                        return 1;
                    }
                    CoinSelector coinSelector;
                    if (selectAddrStr != null) {
                        Address selectAddr;
                        try {
                            selectAddr = wallet.parseAddress(selectAddrStr);
                        } catch (AddressFormatException x) {
                            System.err.println("Could not parse given address, or wrong network: " + selectAddrStr);
                            return 1;
                        }
                        final Address validSelectAddr = selectAddr;
                        coinSelector = CoinSelector.fromPredicate(candidate -> {
                            try {
                                return candidate.getScriptPubKey().getToAddress(net).equals(validSelectAddr);
                            } catch (ScriptException x) {
                                return false;
                            }
                        });
                    } else if (selectOutputStr != null) {
                        String[] parts = selectOutputStr.split(":", 2);
                        Sha256Hash selectTransactionHash = Sha256Hash.wrap(parts[0]);
                        int selectIndex = Integer.parseInt(parts[1]);
                        coinSelector = CoinSelector.fromPredicate(candidate ->
                             candidate.getIndex() == selectIndex && candidate.getParentTransactionHash().equals(selectTransactionHash)
                        );
                    } else {
                        coinSelector = null;
                    }
                    send(coinSelector, outputsStr, feePerVkb, lockTimeStr, allowUnconfirmed);
                } else {
                    System.err.println("You must specify at least one --output=addr:value.");
                    return 1;
                }
                break;
            case ENCRYPT: encrypt(); break;
            case DECRYPT: decrypt(); break;
            case UPGRADE: upgrade(); break;
            case ROTATE: rotate(); break;
            case SET_CREATION_TIME: setCreationTime(); break;
        }

        if (!wallet.isConsistent()) {
            System.err.println("************** WALLET IS INCONSISTENT *****************");
            return 10;
        }

        saveWallet(walletFile);

        if (waitFor != null) {
            setup();
            CompletableFuture<String> futureMessage = wait(waitFor, condition);
            if (!peerGroup.isRunning())
                peerGroup.startAsync();
            System.out.println(futureMessage.join());
            if (!wallet.isConsistent()) {
                System.err.println("************** WALLET IS INCONSISTENT *****************");
                return 10;
            }
            saveWallet(walletFile);
        }
        shutdown();

        return 0;
    }

    private static Protos.Wallet attemptHexConversion(Protos.Wallet proto) {
        // Try to convert any raw hashes and such to textual equivalents for easier debugging. This makes it a bit
        // less "raw" but we will just abort on any errors.
        try {
            Protos.Wallet.Builder builder = proto.toBuilder();
            for (Protos.Transaction tx : builder.getTransactionList()) {
                Protos.Transaction.Builder txBuilder = tx.toBuilder();
                txBuilder.setHash(bytesToHex(txBuilder.getHash()));
                for (int i = 0; i < txBuilder.getBlockHashCount(); i++)
                    txBuilder.setBlockHash(i, bytesToHex(txBuilder.getBlockHash(i)));
                for (Protos.TransactionInput input : txBuilder.getTransactionInputList()) {
                    Protos.TransactionInput.Builder inputBuilder = input.toBuilder();
                    inputBuilder.setTransactionOutPointHash(bytesToHex(inputBuilder.getTransactionOutPointHash()));
                }
                for (Protos.TransactionOutput output : txBuilder.getTransactionOutputList()) {
                    Protos.TransactionOutput.Builder outputBuilder = output.toBuilder();
                    if (outputBuilder.hasSpentByTransactionHash())
                        outputBuilder.setSpentByTransactionHash(bytesToHex(outputBuilder.getSpentByTransactionHash()));
                }
                // TODO: keys, ip addresses etc.
            }
            return builder.build();
        } catch (Throwable throwable) {
            log.error("Failed to do hex conversion on wallet proto", throwable);
            return proto;
        }
    }

    private static ByteString bytesToHex(ByteString bytes) {
        return ByteString.copyFrom(ByteUtils.formatHex(bytes.toByteArray()).getBytes());
    }

    private void upgrade() {
        DeterministicKeyChain activeKeyChain = wallet.getActiveKeyChain();
        if (activeKeyChain == null) {
            System.err.println("Basic keychains cannot be upgraded.");
            return;
        }
        ScriptType currentOutputScriptType = activeKeyChain.getOutputScriptType();
        if (!wallet.isDeterministicUpgradeRequired(outputScriptType)) {
            System.err.println("No upgrade from " + currentOutputScriptType + " to " + outputScriptType);
            return;
        }
        AesKey aesKey = null;
        if (wallet.isEncrypted()) {
            aesKey = passwordToKey(true);
            if (aesKey == null)
                return;
        }
        wallet.upgradeToDeterministic(outputScriptType, aesKey);
        System.out.println("Upgraded from " + currentOutputScriptType + " to " + outputScriptType);
    }

    private void rotate() throws BlockStoreException {
        setup();
        peerGroup.start();
        // Set a key rotation time and possibly broadcast the resulting maintenance transactions.
        Instant rotationTime = TimeUtils.currentTime();
        if (date != null) {
            rotationTime = Instant.from(date);
        } else if (unixtime != null) {
            rotationTime = Instant.ofEpochSecond(unixtime);
        }
        log.info("Setting wallet key rotation time to {}", TimeUtils.dateTimeFormat(rotationTime));
        wallet.setKeyRotationTime(rotationTime);
        AesKey aesKey = null;
        if (wallet.isEncrypted()) {
            aesKey = passwordToKey(true);
            if (aesKey == null)
                return;
        }
        wallet.doMaintenance(aesKey, true).join();
    }

    private void encrypt() {
        if (password == null) {
            System.err.println("You must provide a --password");
            return;
        }
        if (wallet.isEncrypted()) {
            System.err.println("This wallet is already encrypted.");
            return;
        }
        wallet.encrypt(password);
    }

    private void decrypt() {
        if (password == null) {
            System.err.println("You must provide a --password");
            return;
        }
        if (!wallet.isEncrypted()) {
            System.err.println("This wallet is not encrypted.");
            return;
        }
        try {
            wallet.decrypt(password);
        } catch (KeyCrypterException e) {
            System.err.println("Password incorrect.");
        }
    }

    private void addAddr() {
        if (addrStr == null) {
            System.err.println("You must specify an --addr to watch.");
            return;
        }
        try {
            Address address = LegacyAddress.fromBase58(addrStr, net);
            // If no creation time is specified, assume genesis (zero).
            getCreationTime().ifPresentOrElse(
                    creationTime -> wallet.addWatchedAddress(address, creationTime),
                    () -> wallet.addWatchedAddress(address));
        } catch (AddressFormatException e) {
            System.err.println("Could not parse given address, or wrong network: " + addrStr);
        }
    }

    private void send(CoinSelector coinSelector, List<String> outputs, Coin feePerVkb, String lockTimeStr,
                      boolean allowUnconfirmed)
            throws VerificationException {
        Coin balance = coinSelector != null ? wallet.getBalance(coinSelector) : wallet.getBalance(allowUnconfirmed ?
                BalanceType.ESTIMATED : BalanceType.AVAILABLE);
        // Convert the input strings to outputs.
        Transaction tx = new Transaction();
        for (String spec : outputs) {
            try {
                OutputSpec outputSpec = new OutputSpec(spec);
                Coin value = outputSpec.value != null ? outputSpec.value : balance;
                if (outputSpec.isAddress())
                    tx.addOutput(value, outputSpec.addr);
                else
                    tx.addOutput(value, outputSpec.key);
            } catch (AddressFormatException.WrongNetwork e) {
                System.err.println("Malformed output specification, address is for a different network: " + spec);
                return;
            } catch (AddressFormatException e) {
                System.err.println("Malformed output specification, could not parse as address: " + spec);
                return;
            } catch (NumberFormatException e) {
                System.err.println("Malformed output specification, could not parse as value: " + spec);
                return;
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                return;
            }
        }
        boolean emptyWallet = tx.getOutputs().size() == 1 && tx.getOutput(0).getValue().equals(balance);
        if (emptyWallet) {
            log.info("Emptying out wallet, recipient may get less than what you expect");
        }

        AesKey aesKey;
        if (password != null) {
            aesKey = passwordToKey(true);
            if (aesKey == null)
                return;  // Error message already printed.
        } else {
            aesKey = null;
        }

        SendRequest req = buildSendRequest(tx, emptyWallet, allowUnconfirmed, coinSelector, feePerVkb, aesKey);

        try {
            wallet.completeTx(req);
        } catch (InsufficientMoneyException e) {
            System.err.println("Insufficient funds: have " + balance.toFriendlyString());
        }

        try {
            if (lockTimeStr != null) {
                tx.setLockTime(parseLockTimeStr(lockTimeStr));
                // For lock times to take effect, at least one output must have a non-final sequence number.
                tx.replaceInput(0, tx.getInput(0).withSequence(0));
                // And because we modified the transaction after it was completed, we must re-sign the inputs.
                wallet.signTransaction(req);
            }
        } catch (ParseException e) {
            System.err.println("Could not understand --locktime of " + lockTimeStr);
            return;
        } catch (ScriptException e) {
            throw new RuntimeException(e);
        }
        System.out.println("id: " + tx.getTxId());
        System.out.println("tx: " + ByteUtils.formatHex(tx.serialize()));
        if (offline) {
            wallet.commitTx(tx);
            return;
        }

        try {
            setup();
            peerGroup.start();
        } catch (BlockStoreException e) {
            throw new RuntimeException(e);
        }

        try {
            // Wait for peers to connect, the tx to be sent to one of them and for it to be propagated across the
            // network. Once propagation is complete and we heard the transaction back from all our peers, it will
            // be committed to the wallet.
            peerGroup.broadcastTransaction(tx).awaitRelayed().get();
            // Hack for regtest/single peer mode, as we're about to shut down and won't get an ACK from the remote end.
            List<Peer> peerList = peerGroup.getConnectedPeers();
            if (peerList.size() == 1)
                peerList.get(0).sendPing().get();
        } catch (ExecutionException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // "Atomically" create a SendRequest. In the future SendRequest may be immutable and this method will be updated
    private SendRequest buildSendRequest(Transaction tx, boolean emptyWallet, boolean allowUnconfirmed, @Nullable CoinSelector coinSelector, @Nullable Coin feePerVkb, @Nullable AesKey aesKey) {
        SendRequest req = SendRequest.forTx(tx);
        req.emptyWallet = emptyWallet;
        if (coinSelector != null) {
            req.coinSelector = coinSelector;
            req.recipientsPayFees = true;
        }
        if (allowUnconfirmed) {
            // Note that this will overwrite the CoinSelector set above
            req.allowUnconfirmed();
        }
        if (feePerVkb != null)
            req.setFeePerVkb(feePerVkb);
        req.aesKey = aesKey;
        return req;
    }

    static class OutputSpec {
        public final Coin value;
        public final Address addr;
        public final ECKey key;

        public OutputSpec(String spec) throws IllegalArgumentException {
            String[] parts = spec.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Malformed output specification, must have two parts separated by :");
            }
            String destination = parts[0];
            if ("ALL".equalsIgnoreCase(parts[1]))
                value = null;
            else
                value = parseCoin(parts[1]);
            if (destination.startsWith("0")) {
                // Treat as a raw public key.
                byte[] pubKey = new BigInteger(destination, 16).toByteArray();
                key = ECKey.fromPublicOnly(pubKey);
                addr = null;
            } else {
                // Treat as an address.
                addr = wallet.parseAddress(destination);
                key = null;
            }
        }

        public boolean isAddress() {
            return addr != null;
        }
    }

    /**
     * Parses the string either as a whole number of blocks, or if it contains slashes as a YYYY/MM/DD format date
     * and returns the lock time in wire format.
     */
    private static long parseLockTimeStr(String lockTimeStr) throws ParseException {
        if (lockTimeStr.contains("/")) {
            Instant time = Instant.from(DateTimeFormatter.ofPattern("yyyy/MM/dd").parse(lockTimeStr));
            return time.getEpochSecond();
        }
        return Long.parseLong(lockTimeStr);
    }

    /**
     * Wait for a condition to be satisfied
     *
     * @param waitFor   condition type to wait for
     * @param condition balance condition to wait for
     * @return A (future) human-readable message (txId, block hash, or balance) to display when wait is complete
     */
    private CompletableFuture<String> wait(WaitForEnum waitFor, Condition condition) {
        CompletableFuture<String> future = new CompletableFuture<>();
        switch (waitFor) {
            case EVER:
                break;  // Future will never complete

            case WALLET_TX:
                // Future will complete with a transaction ID string
                Consumer<Transaction> txListener = tx -> future.complete(tx.getTxId().toString());
                // Both listeners run in a peer thread
                wallet.addCoinsReceivedEventListener((wallet, tx, prevBalance, newBalance) -> txListener.accept(tx));
                wallet.addCoinsSentEventListener((wallet, tx, prevBalance, newBalance) -> txListener.accept(tx));
                break;

            case BLOCK:
                // Future will complete with a Block hash string
                peerGroup.addBlocksDownloadedEventListener((peer, block, filteredBlock, blocksLeft) ->
                    future.complete(block.getHashAsString())
                );
                break;

            case BALANCE:
                // Future will complete with a balance amount string
                // Check if the balance already meets the given condition.
                Coin existingBalance = wallet.getBalance(Wallet.BalanceType.ESTIMATED);
                if (condition.matchBitcoins(existingBalance)) {
                    future.complete(existingBalance.toFriendlyString());
                } else {
                    Runnable onChange = () -> {
                        synchronized (this) {
                            saveWallet(walletFile);
                            Coin balance = wallet.getBalance(Wallet.BalanceType.ESTIMATED);
                            if (condition.matchBitcoins(balance)) {
                                future.complete(balance.toFriendlyString());
                            }
                        }
                    };
                    wallet.addCoinsReceivedEventListener((w, t, p, n) -> onChange.run());
                    wallet.addCoinsSentEventListener((w, t, p, n) -> onChange.run());
                    wallet.addChangeEventListener(w -> onChange.run());
                    wallet.addReorganizeEventListener(w -> onChange.run());
                }
                break;
        }
        return future;
    }

    private void reset() {
        // Delete the transactions and save. In future, reset the chain head pointer.
        wallet.clearTransactions(0);
        saveWallet(walletFile);
    }

    // Sets up all objects needed for network communication but does not bring up the peers.
    private void setup() throws BlockStoreException {
        if (store != null) return;  // Already done.
        // Will create a fresh chain if one doesn't exist or there is an issue with this one.
        boolean reset = !chainFile.exists();
        if (reset) {
            // No chain, so reset the wallet as we will be downloading from scratch.
            System.out.println("Chain file is missing so resetting the wallet.");
            reset();
        }
        store = new SPVBlockStore(params, chainFile);
        if (reset) {
            try {
                CheckpointManager.checkpoint(params, CheckpointManager.openStream(params), store,
                        wallet.earliestKeyCreationTime());
                StoredBlock head = store.getChainHead();
                System.out.println("Skipped to checkpoint " + head.getHeight() + " at "
                        + TimeUtils.dateTimeFormat(head.getHeader().time()));
            } catch (IOException x) {
                System.out.println("Could not load checkpoints: " + x.getMessage());
            }
        }
        chain = new BlockChain(net, wallet, store);
        // This will ensure the wallet is saved when it changes.
        wallet.autosaveToFile(walletFile, Duration.ofSeconds(5), null);
        if (peerGroup == null) {
            peerGroup = new PeerGroup(net, chain);
        }
        peerGroup.setUserAgent("WalletTool", "1.0");
        if (net == BitcoinNetwork.REGTEST) {
            peerGroup.addAddress(PeerAddress.localhost(params));
            peerGroup.setMinBroadcastConnections(1);
            peerGroup.setMaxConnections(1);
        }
        peerGroup.addWallet(wallet);
        peerGroup.setBloomFilteringEnabled(filter == Filter.SERVER);
        if (peersStr != null) {
            String[] peerAddrs = peersStr.split(",");
            for (String peer : peerAddrs) {
                try {
                    peerGroup.addAddress(PeerAddress.simple(InetAddress.getByName(peer), params.getPort()));
                } catch (UnknownHostException e) {
                    System.err.println("Could not understand peer domain name/IP address: " + peer + ": " + e.getMessage());
                    System.exit(1);
                }
            }
        } else {
            peerGroup.setRequiredServices(0);
        }
    }

    private void syncChain() {
        try {
            setup();
            int startTransactions = wallet.getTransactions(true).size();
            DownloadProgressTracker listener = new DownloadProgressTracker();
            peerGroup.start();
            peerGroup.startBlockChainDownload(listener);
            try {
                listener.await();
            } catch (InterruptedException e) {
                System.err.println("Chain download interrupted, quitting ...");
                System.exit(1);
            }
            int endTransactions = wallet.getTransactions(true).size();
            if (endTransactions > startTransactions) {
                System.out.println("Synced " + (endTransactions - startTransactions) + " transactions.");
            }
        } catch (BlockStoreException e) {
            System.err.println("Error reading block chain file " + chainFile + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void shutdown() {
        try {
            if (peerGroup == null) return;  // setup() never called so nothing to do.
            if (peerGroup.isRunning())
                peerGroup.stop();
            saveWallet(walletFile);
            store.close();
            wallet = null;
        } catch (BlockStoreException e) {
            throw new RuntimeException(e);
        }
    }

    private void createWallet(Network network, File walletFile) throws IOException {
        KeyChainGroupStructure keyChainGroupStructure = KeyChainGroupStructure.BIP32;

        if (walletFile.exists() && !force) {
            System.err.println("Wallet creation requested but " + walletFile + " already exists, use --force");
            return;
        }
        Instant creationTime = getCreationTime().orElse(MnemonicCode.BIP39_STANDARDISATION_TIME);
        if (seedStr != null) {
            DeterministicSeed seed;
            // Parse as mnemonic code.
            final List<String> split = splitMnemonic(seedStr);
            String passphrase = ""; // TODO allow user to specify a passphrase
            seed = DeterministicSeed.ofMnemonic(split, passphrase, creationTime);
            try {
                seed.check();
            } catch (MnemonicException.MnemonicLengthException e) {
                System.err.println("The seed did not have 12 words in, perhaps you need quotes around it?");
                return;
            } catch (MnemonicException.MnemonicWordException e) {
                System.err.println("The seed contained an unrecognised word: " + e.badWord);
                return;
            } catch (MnemonicException.MnemonicChecksumException e) {
                System.err.println("The seed did not pass checksumming, perhaps one of the words is wrong?");
                return;
            } catch (MnemonicException e) {
                // not reached - all subclasses handled above
                throw new RuntimeException(e);
            }
            wallet = Wallet.fromSeed(network, seed, outputScriptType, keyChainGroupStructure);
        } else if (watchKeyStr != null) {
            wallet = Wallet.fromWatchingKeyB58(network, watchKeyStr, creationTime);
        } else {
            wallet = Wallet.createDeterministic(network, outputScriptType, keyChainGroupStructure);
        }
        if (password != null)
            wallet.encrypt(password);
        wallet.saveToFile(walletFile);
    }

    private List<String> splitMnemonic(String seedStr) {
        return Stream.of(seedStr.split("[ :;,]")) // anyOf(" :;,")
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }

    private void saveWallet(File walletFile) {
        try {
            // This will save the new state of the wallet to a temp file then rename, in case anything goes wrong.
            wallet.saveToFile(walletFile);
        } catch (IOException e) {
            System.err.println("Failed to save wallet! Old wallet should be left untouched.");
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void addKey() {
        ECKey key;
        Optional<Instant> creationTime = getCreationTime();
        if (privKeyStr != null) {
            try {
                DumpedPrivateKey dpk = DumpedPrivateKey.fromBase58(net, privKeyStr); // WIF
                key = dpk.getKey();
            } catch (AddressFormatException e) {
                byte[] decode = parseAsHexOrBase58(privKeyStr);
                if (decode == null) {
                    System.err.println("Could not understand --privkey as either WIF, hex or base58: " + privKeyStr);
                    return;
                }
                key = ECKey.fromPrivate(ByteUtils.bytesToBigInteger(decode));
            }
            if (pubKeyStr != null) {
                // Give the user a hint.
                System.out.println("You don't have to specify --pubkey when a private key is supplied.");
            }
            creationTime.ifPresentOrElse(key::setCreationTime, key::clearCreationTime);
        } else if (pubKeyStr != null) {
            byte[] pubkey = parseAsHexOrBase58(pubKeyStr);
            key = ECKey.fromPublicOnly(pubkey);
            creationTime.ifPresentOrElse(key::setCreationTime, key::clearCreationTime);
        } else {
            System.err.println("Either --privkey or --pubkey must be specified.");
            return;
        }
        if (wallet.hasKey(key)) {
            System.err.println("That key already exists in this wallet.");
            return;
        }
        try {
            if (wallet.isEncrypted()) {
                AesKey aesKey = passwordToKey(true);
                if (aesKey == null)
                    return;   // Error message already printed.
                key = key.encrypt(Objects.requireNonNull(wallet.getKeyCrypter()), aesKey);
            }
        } catch (KeyCrypterException kce) {
            System.err.println("There was an encryption related error when adding the key. The error was '"
                    + kce.getMessage() + "'.");
            return;
        }
        if (!key.isCompressed())
            System.out.println("WARNING: Importing an uncompressed key");
        wallet.importKey(key);
        System.out.print("Addresses: " + key.toAddress(ScriptType.P2PKH, net));
        if (key.isCompressed())
            System.out.print("," + key.toAddress(ScriptType.P2WPKH, net));
        System.out.println();
    }

    @Nullable
    private AesKey passwordToKey(boolean printError) {
        if (password == null) {
            if (printError)
                System.err.println("You must provide a password.");
            return null;
        }
        if (!wallet.checkPassword(password)) {
            if (printError)
                System.err.println("The password is incorrect.");
            return null;
        }
        return Objects.requireNonNull(wallet.getKeyCrypter()).deriveKey(password);
    }

    /**
     * Attempts to parse the given string as arbitrary-length hex or base58 and then return the results, or null if
     * neither parse was successful.
     */
    private byte[] parseAsHexOrBase58(String data) {
        try {
            return ByteUtils.parseHex(data);
        } catch (Exception e) {
            // Didn't decode as hex, try base58.
            try {
                return Base58.decodeChecked(data);
            } catch (AddressFormatException e1) {
                return null;
            }
        }
    }

    private Optional<Instant> getCreationTime() {
        if (unixtime != null)
            return Optional.of(Instant.ofEpochSecond(unixtime));
        else if (date != null)
            return Optional.of(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
        else
            return Optional.empty();
    }

    private void deleteKey() {
        if (pubKeyStr == null && addrStr == null) {
            System.err.println("One of --pubkey or --addr must be specified.");
            return;
        }
        ECKey key;
        if (pubKeyStr != null) {
            key = wallet.findKeyFromPubKey(ByteUtils.parseHex(pubKeyStr));
        } else {
            try {
                Address address = wallet.parseAddress(addrStr);
                key = wallet.findKeyFromAddress(address);
            } catch (AddressFormatException e) {
                System.err.println(addrStr + " does not parse as a Bitcoin address of the right network parameters.");
                return;
            }
        }
        if (key == null) {
            System.err.println("Wallet does not seem to contain that key.");
            return;
        }
        boolean removed = wallet.removeKey(key);
        if (removed)
            System.out.println("Key " + key + " was removed");
        else
            System.err.println("Key " + key + " could not be removed");
    }

    private void currentReceiveAddr() {
        Address address = wallet.currentReceiveAddress();
        System.out.println(address);
    }

    private void dumpWallet() throws BlockStoreException {
        // Setup to get the chain height so we can estimate lock times, but don't wipe the transactions if it's not
        // there just for the dump case.
        if (chainFile.exists())
            setup();

        if (dumpPrivKeys && wallet.isEncrypted()) {
            if (password != null) {
                final AesKey aesKey = passwordToKey(true);
                if (aesKey == null)
                    return; // Error message already printed.
                printWallet(aesKey);
            } else {
                System.err.println("Can't dump privkeys, wallet is encrypted.");
                return;
            }
        } else {
            printWallet(null);
        }
    }

    private void printWallet(@Nullable AesKey aesKey) {
        System.out.println(wallet.toString(dumpLookAhead, dumpPrivKeys, aesKey, true, true, chain));
    }

    private void setCreationTime() {
        Optional<Instant> creationTime = getCreationTime();
        for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
            DeterministicSeed seed = chain.getSeed();
            if (seed == null)
                System.out.println("Active chain does not have a seed: " + chain);
            else
                creationTime.ifPresentOrElse(seed::setCreationTime, seed::clearCreationTime);

        }
        System.out.println(creationTime
                .map(time -> "Setting creation time to: " + TimeUtils.dateTimeFormat(time))
                .orElse("Clearing creation time."));
    }
}
