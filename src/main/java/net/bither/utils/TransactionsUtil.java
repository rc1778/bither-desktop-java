/*
 * Copyright 2014 http://Bither.net
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

package net.bither.utils;


import net.bither.BitherSetting;
import net.bither.api.BitherMytransactionsApi;
import net.bither.api.GetInSignaturesApi;
import net.bither.bitherj.BitherjSettings;
import net.bither.bitherj.core.*;
import net.bither.bitherj.exception.AddressFormatException;
import net.bither.bitherj.exception.ScriptException;
import net.bither.bitherj.exception.VerificationException;
import net.bither.bitherj.qrcode.QRCodeUtil;
import net.bither.bitherj.script.Script;
import net.bither.bitherj.utils.Sha256Hash;
import net.bither.bitherj.utils.Utils;
import net.bither.http.HttpSetting;
import net.bither.preference.UserPreference;
import org.apache.commons.codec.binary.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.util.*;

public class TransactionsUtil {

    public static long reloadTxTime = -1;

    private static final String EXPLORER_VERSION = "ver";
    private static final String EXPLORER_IN = "in";
    private static final String EXPLORER_OUT = "out";

    private static final String EXPLORER_OUT_ADDRESS = "address";
    private static final String EXPLORER_COINBASE = "coinbase";
    private static final String EXPLORER_SEQUENCE = "sequence";
    private static final String EXPLORER_TIME = "time";

    private static final String TXS = "txs";
    private static final String BITHER_BLOCK_HASH = "block_hash";
    private static final String TX_HASH = "tx_hash";
    private static final String BITHER_BLOCK_NO = "block_no";
    private static final String BITHER_VALUE = "val";
    private static final String PREV_TX_HASH = "prev";
    private static final String PREV_OUTPUT_SN = "n";
    private static final String SCRIPT_PUB_KEY = "script";
    private static final String BLOCK_COUNT = "block_count";

    private static final byte[] EMPTY_BYTES = new byte[32];

    private static List<UnSignTransaction> unsignTxs = new ArrayList<UnSignTransaction>();

    public static List<Tx> getTransactionsFromBither(
            JSONObject jsonObject, int storeBlockHeight) throws JSONException,
            AddressFormatException,
            VerificationException, ParseException, NoSuchFieldException,
            IllegalAccessException, IllegalArgumentException {
        List<Tx> transactions = new ArrayList<Tx>();

        if (!jsonObject.isNull(TXS)) {
            JSONArray txArray = jsonObject.getJSONArray(TXS);
            double count = 0;
            double size = txArray.length();

            for (int j = 0; j < txArray.length(); j++) {
                JSONObject tranJsonObject = txArray.getJSONObject(j);
                String txHash = tranJsonObject.getString(TX_HASH);
                byte[] txHashByte = Utils.reverseBytes(Utils.hexStringToByteArray(txHash));
                int height = tranJsonObject.getInt(BITHER_BLOCK_NO);
                if (height > storeBlockHeight && storeBlockHeight > 0) {
                    continue;
                }
                int version = 1;

                int updateTime = (int) (new Date().getTime() / 1000);
                if (!tranJsonObject.isNull(EXPLORER_TIME)) {
                    updateTime = (int) (DateUtils
                            .getDateTimeForTimeZone(tranJsonObject
                                    .getString(EXPLORER_TIME)).getTime() / 1000);
                }
                if (!tranJsonObject.isNull(EXPLORER_VERSION)) {
                    version = tranJsonObject.getInt(EXPLORER_VERSION);

                }
                Tx tx = new Tx();
                tx.setTxHash(txHashByte);
                tx.setTxTime(updateTime);
                if (!tranJsonObject.isNull(EXPLORER_OUT)) {
                    JSONArray tranOutArray = tranJsonObject
                            .getJSONArray(EXPLORER_OUT);
                    for (int i = 0; i < tranOutArray.length(); i++) {
                        JSONObject tranOutJson = tranOutArray.getJSONObject(i);
                        long value = tranOutJson
                                .getLong(BITHER_VALUE);
                        if (!tranOutJson.isNull(SCRIPT_PUB_KEY)) {
                            String str = tranOutJson.getString(SCRIPT_PUB_KEY);
                            Out out = new Out(
                                    tx, value,
                                    Utils.hexStringToByteArray(str));
                            out.setTxHash(txHashByte);
                            out.setOutSn(i);
                            tx.addOutput(out);
                        }

                    }

                }

                if (!tranJsonObject.isNull(EXPLORER_IN)) {
                    JSONArray tranInArray = tranJsonObject
                            .getJSONArray(EXPLORER_IN);
                    for (int i = 0; i < tranInArray.length(); i++) {
                        JSONObject tranInJson = tranInArray.getJSONObject(i);
                        In in = new In();
                        in.setTxHash(txHashByte);
                        if (!tranInJson.isNull(EXPLORER_COINBASE)) {
                            int index = 0;
                            if (!tranInJson.isNull(EXPLORER_SEQUENCE)) {
                                index = tranInJson.getInt(EXPLORER_SEQUENCE);
                            }
                            in.setPrevTxHash(Sha256Hash.ZERO_HASH.getBytes());
                            in.setPrevOutSn(index);
                        } else {

                            String prevOutHash = tranInJson
                                    .getString(PREV_TX_HASH);
                            int n = 0;
                            if (!tranInJson.isNull(PREV_OUTPUT_SN)) {
                                n = tranInJson.getInt(PREV_OUTPUT_SN);
                            }
                            in.setPrevTxHash(Utils.reverseBytes(Utils.hexStringToByteArray(prevOutHash)));
                            in.setPrevOutSn(n);

                        }
                        in.setInSn(i);
                        in.setPrevOutScript(Script.createInputScript(EMPTY_BYTES, EMPTY_BYTES));
                        tx.addInput(in);


                    }
                }
                tx.setTxVer(version);
                tx.setBlockNo(height);
                for (Tx temp : transactions) {
                    if (temp.getBlockNo() == tx.getBlockNo()) {
                        boolean marketSpent = false;
                        for (In tempIn : temp.getIns()) {
                            if (Arrays.equals(tempIn.getPrevTxHash(), tx.getTxHash())) {
                                tx.setTxTime(temp.getTxTime() - 1);
                                marketSpent = true;

                            }
                        }

                        if (!marketSpent) {
                            for (In tempIn : tx.getIns()) {
                                if (Arrays.equals(tempIn.getPrevTxHash(), temp.getTxHash())) {
                                    tx.setTxTime(temp.getTxTime() + 1);
                                }
                            }
                        }
                    }
                }

                transactions.add(tx);

                count++;
//                double progress = BitherSetting.SYNC_TX_PROGRESS_BLOCK_HEIGHT
//                        + BitherSetting.SYNC_TX_PROGRESS_STEP1
//                        + BitherSetting.SYNC_TX_PROGRESS_STEP2 * (count / size);
//                BroadcastUtil.sendBroadcastProgressState(progress);

            }

        }
        //  Collections.sort(transactions, new ComparatorTx());

        return transactions;

    }

    public static List<In> getInSignatureFromBither(String str) {
        List<In> result = new ArrayList<In>();
        if (str.length() > 0) {
            String[] txs = str.split(";");
            for (String tx : txs) {
                String[] ins = tx.split(":");
//todo base64
                byte[] txHash = Utils.reverseBytes(Base64.decodeBase64(ins[0]));
                for (int i = 1; i < ins.length; i++) {
                    String[] array = ins[i].split(",");
                    int inSn = Integer.decode(array[0]);
                    byte[] inSignature = Base64.decodeBase64(array[1]);
                    In in = new In();
                    in.setTxHash(txHash);
                    in.setInSn(inSn);
                    in.setInSignature(inSignature);
                    result.add(in);
                }
            }
        }
        return result;
    }

    public static class ComparatorTx implements Comparator<Tx> {

        @Override
        public int compare(Tx lhs, Tx rhs) {
            if (lhs.getBlockNo() != rhs.getBlockNo()) {
                return Integer.valueOf(lhs.getBlockNo()).compareTo(Integer.valueOf(rhs.getBlockNo()));
            } else {
                return Integer.valueOf(lhs.getTxTime()).compareTo(Integer.valueOf(rhs.getTxTime()));
            }

        }

    }

    // TODO display unSignTx
    public static UnSignTransaction getUnsignTxFromCache(String address) {
        synchronized (unsignTxs) {
            for (UnSignTransaction unSignTransaction : unsignTxs) {
                if (Utils.compareString(address,
                        unSignTransaction.getAddress())) {
                    return unSignTransaction;
                }
            }
            return null;
        }

    }

    public static void removeSignTx(UnSignTransaction unSignTransaction) {
        synchronized (unsignTxs) {
            if (unsignTxs.contains(unSignTransaction)) {
                unsignTxs.remove(unSignTransaction);
            }
        }
    }

    public static void addUnSignTxToCache(UnSignTransaction unSignTransaction) {
        synchronized (unsignTxs) {
            if (unsignTxs.contains(unSignTransaction)) {
                unsignTxs.remove(unSignTransaction);
            }
            unsignTxs.add(unSignTransaction);
        }
    }

    public static boolean signTransaction(Tx tx, String qrCodeContent)
            throws ScriptException {
        String[] stringArray = QRCodeUtil.splitString(qrCodeContent);
        List<byte[]> hashList = new ArrayList<byte[]>();
        for (String str : stringArray) {
            if (!Utils.isEmpty(str)) {
                hashList.add(Utils.hexStringToByteArray(str));
            }
        }
        tx.signWithSignatures(hashList);
        return tx.verifySignatures();
    }


    public static BitherSetting.AddressType checkAddress(List<String> addressList) throws Exception {
        for (String address : addressList) {
            BitherMytransactionsApi bitherMytransactionsApi = new BitherMytransactionsApi(address.toString());
            bitherMytransactionsApi.handleHttpGet();
            String result = bitherMytransactionsApi.getResult();
            JSONObject json = new JSONObject(result);
            if (!json.isNull(HttpSetting.SPECIAL_TYPE)) {
                int specialType = json.getInt(HttpSetting.SPECIAL_TYPE);
                if (specialType == 0) {
                    return BitherSetting.AddressType.SpecialAddress;
                } else {
                    return BitherSetting.AddressType.TxTooMuch;
                }
            }
        }
        return BitherSetting.AddressType.Normal;
    }

    public static void getMyTxFromBither() throws Exception {
        if (UserPreference.getInstance().getAppMode() != BitherjSettings.AppMode.HOT) {
            return;
        }
        Block storedBlock = BlockChain.getInstance().getLastBlock();

        int storeBlockHeight = storedBlock.getBlockNo();
        for (Address address : AddressManager.getInstance().getAllAddresses()) {
            if (!address.isSyncComplete()) {
                List<Tx> transactions = new ArrayList<Tx>();
                int apiBlockCount = 0;
                BitherMytransactionsApi bitherMytransactionsApi = new BitherMytransactionsApi(
                        address.getAddress());
                bitherMytransactionsApi.handleHttpGet();
                String txResult = bitherMytransactionsApi.getResult();
                JSONObject jsonObject = new JSONObject(txResult);
                if (!jsonObject.isNull(BLOCK_COUNT)) {
                    apiBlockCount = jsonObject.getInt(BLOCK_COUNT);
                }
                List<Tx> temp = TransactionsUtil.getTransactionsFromBither(
                        jsonObject, storeBlockHeight);
                transactions.addAll(temp);

                if (apiBlockCount < storeBlockHeight && storeBlockHeight - apiBlockCount < 100) {
                    BlockChain.getInstance().rollbackBlock(apiBlockCount);
                }
                Collections.sort(transactions, new ComparatorTx());
                address.initTxs(transactions);
                address.setSyncComplete(true);
                address.updateSyncComplete();
//            BroadcastUtil
//                    .sendBroadcastProgressState(BitherSetting.SYNC_PROGRESS_COMPLETE);
            }


        }
    }

    public static Thread completeInputsForAddressInBackground(final Address address) {
        Thread thread = new Thread() {
            @Override
            public void run() {
                completeInputsForAddress(address);
            }
        };
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        return thread;
    }

    public static void completeInputsForAddress(Address address) {
        try {
            int fromBlock = address.needCompleteInSignature();
            while (fromBlock > 0) {
                GetInSignaturesApi api = new GetInSignaturesApi(address.getAddress(), fromBlock);
                api.handleHttpGet();
                address.completeInSignature(getInSignatureFromBither(api.getResult()));
                fromBlock = address.needCompleteInSignature();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean canReloadTx() {
        if (reloadTxTime == -1) {
            return true;
        } else {
            return reloadTxTime + 60 * 60 * 1000 < System.currentTimeMillis();
        }
    }

}
