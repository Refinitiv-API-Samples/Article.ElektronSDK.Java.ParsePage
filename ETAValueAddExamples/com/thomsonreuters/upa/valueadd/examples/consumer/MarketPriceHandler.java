package com.thomsonreuters.upa.valueadd.examples.consumer;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


import com.thomsonreuters.upa.codec.AckMsg;
import com.thomsonreuters.upa.codec.Buffer;
import com.thomsonreuters.upa.codec.CodecFactory;
import com.thomsonreuters.upa.codec.CodecReturnCodes;
import com.thomsonreuters.upa.codec.DataDictionary;
import com.thomsonreuters.upa.codec.DataTypes;
import com.thomsonreuters.upa.codec.DateTime;
import com.thomsonreuters.upa.codec.DecodeIterator;
import com.thomsonreuters.upa.codec.DictionaryEntry;
import com.thomsonreuters.upa.codec.EncodeIterator;
import com.thomsonreuters.upa.codec.EnumType;
import com.thomsonreuters.upa.codec.FieldEntry;
import com.thomsonreuters.upa.codec.FieldList;
import com.thomsonreuters.upa.codec.Int;
import com.thomsonreuters.upa.codec.Msg;
import com.thomsonreuters.upa.codec.MsgClasses;
import com.thomsonreuters.upa.codec.MsgKey;
import com.thomsonreuters.upa.codec.PostUserInfo;
import com.thomsonreuters.upa.codec.Qos;
import com.thomsonreuters.upa.codec.Real;
import com.thomsonreuters.upa.codec.RefreshMsg;
import com.thomsonreuters.upa.codec.State;
import com.thomsonreuters.upa.codec.StatusMsg;
import com.thomsonreuters.upa.codec.StreamStates;
import com.thomsonreuters.upa.codec.Time;
import com.thomsonreuters.upa.codec.UInt;
import com.thomsonreuters.upa.codec.UpdateMsg;
import com.thomsonreuters.upa.valueadd.examples.common.CacheHandler;
import com.thomsonreuters.upa.valueadd.examples.common.CacheInfo;
import com.thomsonreuters.upa.valueadd.examples.consumer.StreamIdWatchList.StreamIdKey;
import com.thomsonreuters.upa.valueadd.examples.consumer.StreamIdWatchList.WatchListEntry;
import com.thomsonreuters.upa.valueadd.reactor.ReactorChannel;
import com.thomsonreuters.upa.valueadd.reactor.ReactorErrorInfo;
import com.thomsonreuters.upa.valueadd.reactor.ReactorFactory;
import com.thomsonreuters.upa.valueadd.reactor.ReactorReturnCodes;
import com.thomsonreuters.upa.valueadd.reactor.ReactorSubmitOptions;
import com.thomsonreuters.upa.shared.rdm.marketprice.MarketPriceClose;
import com.thomsonreuters.upa.shared.rdm.marketprice.MarketPriceRequest;
import com.thomsonreuters.upa.rdm.DomainTypes;
import com.thomsonreuters.upa.rdm.UpdateEventTypes;
import com.thomsonreuters.upa.transport.TransportBuffer;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.directory.Service;
import com.thomsonreuters.upa.valueadd.domainrep.rdm.login.LoginRefresh;
//pimchaya
import java.util.TreeMap;
import javax.xml.bind.DatatypeConverter;
/*
 * This is the market price handler for the UPA Value Add consumer application.
 * It provides methods for sending the market price request(s) to a provider
 * and processing the response(s). Methods for decoding a field entry from a
 * response, and closing market price streams are also provided.
 */
class MarketPriceHandler
{
	private int TRANSPORT_BUFFER_SIZE_REQUEST = 1000;
	private int TRANSPORT_BUFFER_SIZE_CLOSE = 1000;

    private int domainType;
    
    /* login stream id */
    int loginStreamId;

    /* Channel to use for private stream redirect */
    protected ReactorChannel redirectChnl;

    /* Login information to use for private stream redirect */
    private LoginRefresh redirectLoginInfo;

    /* Source directory information to use for private stream redirect */
    private Service redirectSrcDirInfo;

    private MarketPriceRequest marketPriceRequest;

    private MarketPriceClose closeMessage;

    protected final StreamIdWatchList watchList;

    private boolean viewRequested = false;
    private boolean snapshotRequested = false;
    private List<String> viewFieldList;

    // temp. reusable variables used for encoding
    protected FieldList fieldList = CodecFactory.createFieldList();
    protected FieldEntry fieldEntry = CodecFactory.createFieldEntry();
    private UInt fidUIntValue = CodecFactory.createUInt();
    private Int fidIntValue = CodecFactory.createInt();
    private Real fidRealValue = CodecFactory.createReal();
    private com.thomsonreuters.upa.codec.Enum fidEnumValue = CodecFactory.createEnum();
    private com.thomsonreuters.upa.codec.Date fidDateValue = CodecFactory.createDate();
    private Time fidTimeValue = CodecFactory.createTime();
    private DateTime fidDateTimeValue = CodecFactory.createDateTime();
    private com.thomsonreuters.upa.codec.Float fidFloatValue = CodecFactory.createFloat();
    private com.thomsonreuters.upa.codec.Double fidDoubleValue = CodecFactory.createDouble();
    private Qos fidQosValue = CodecFactory.createQos();
    private State fidStateValue = CodecFactory.createState();
    private EncodeIterator encIter = CodecFactory.createEncodeIterator();

    private ReactorSubmitOptions submitOptions = ReactorFactory.createReactorSubmitOptions();
    //pimchaya
    //keep all fields of the rics regardless they are page fields or not
    TreeMap <Integer, String> allFieldsMap = new TreeMap <Integer, String>();
    //keep only page fields which can have partial update
    TreeMap <Integer, String> pageMap = new TreeMap <Integer, String>();
    MarketPriceHandler(StreamIdWatchList watchList)
    {
        this(DomainTypes.MARKET_PRICE, watchList);
    }

    protected MarketPriceHandler(int domainType, StreamIdWatchList watchList)
    {
        this.watchList = watchList;
        this.domainType = domainType;
        marketPriceRequest = createMarketPriceRequest();
        closeMessage = new MarketPriceClose();
        viewFieldList = new ArrayList<String>();
        viewFieldList.add("6");
        viewFieldList.add("22");
        viewFieldList.add("25");
        viewFieldList.add("32");
    }

    protected MarketPriceRequest createMarketPriceRequest()
    {
        return new MarketPriceRequest();
    }

    void viewRequest(boolean doViewRequest)
    {
        viewRequested = doViewRequest;
    }

    void snapshotRequest(boolean snapshotRequested)
    {
        this.snapshotRequested = snapshotRequested;
    }

    protected void removeMarketPriceItemEntry(int streamId)
    {
        watchList.remove(streamId);
    }

    protected int closeStream(ReactorChannel chnl, int streamId, ReactorErrorInfo errorInfo)
    {
        /* get a buffer for the item close */
        TransportBuffer msgBuf = chnl.getBuffer(TRANSPORT_BUFFER_SIZE_CLOSE, false, errorInfo);
        if (msgBuf == null)
            return ReactorReturnCodes.FAILURE;

        /* encode item close */
        closeMessage.clear();
        closeMessage.streamId(streamId);
        closeMessage.domainType(domainType);
        encIter.clear();
        encIter.setBufferAndRWFVersion(msgBuf, chnl.majorVersion(), chnl.minorVersion());

        int ret = closeMessage.encode(encIter);
        if (ret != CodecReturnCodes.SUCCESS)
        {
            System.out.println("encodeMarketPriceClose(): Failed <" + CodecReturnCodes.toString(ret) + ">");
        }
      
		return chnl.submit(msgBuf, submitOptions, errorInfo);
    }

    /*
     * This method is used while posting to query the first requested market
     * price item, if any. It will populate the passed in buffer with the name
     * and length information and return the streamId associated with the
     * stream. If mpItemName->length is 0 and streamId is returned as 0, this
     * indicates that there is no valid name available.
     */
    int getFirstItem(Buffer mpItemName)
    {
        return watchList.getFirstItem(mpItemName);
    }

    private boolean hasMarketPriceCapability(List<Long> capabilities)
    {
        for (Long capability : capabilities)
        {
            if (capability.equals((long)marketPriceRequest.domainType()))
                return true;
        }
        return false;
    }

    /*
     * Encodes and sends item requests for three market price domains
     * (MarketPrice, MarketByPrice, MarketByOrder).
     */
    int sendItemRequests(ReactorChannel chnl, List<String> itemNames, boolean isPrivateStream, LoginRefresh loginInfo,
            Service serviceInfo, ReactorErrorInfo errorInfo)
    {
        if (itemNames == null || itemNames.isEmpty())
            return CodecReturnCodes.SUCCESS;

        /* check to see if the provider supports the market price domain */
        if (!hasMarketPriceCapability(serviceInfo.info().capabilitiesList()))
        {
            errorInfo.error().text("'" +
                    DomainTypes.toString(marketPriceRequest.domainType()) +
                    "' not supported by the indicated provider");
            return CodecReturnCodes.FAILURE;
        }

        /* set redirect channel for private stream redirect */
        redirectChnl = chnl;

        /* set login information for private stream redirect */
        redirectLoginInfo = loginInfo;

        /* set source directory information for private stream redirect */
        redirectSrcDirInfo = serviceInfo;

        generateRequest(marketPriceRequest, isPrivateStream, serviceInfo, loginInfo);

        // If there is only one item in the itemList, it is a waste of bandwidth
        // to send a batch request
        if (itemNames.size() == 1)
        {
            return sendRequest(chnl, itemNames, errorInfo);
        }

        if (!(loginInfo.checkHasFeatures() && 
              loginInfo.features().checkHasSupportBatchRequests() && 
              loginInfo.features().supportBatchRequests() == 1))
        {
            System.out.println("Connected Provider does not support Batch Requests. Sending Market Price requests as individual request messages.");
            return sendRequest(chnl, itemNames, errorInfo);
        }

        // batch
        return sendBatchRequest(chnl, itemNames, errorInfo);
    }

    private void generateRequest(MarketPriceRequest marketPriceRequest, boolean isPrivateStream, Service srcDirInfo, LoginRefresh loginInfo)
    {
        marketPriceRequest.clear();

        if (!snapshotRequested)
            marketPriceRequest.applyStreaming();
        marketPriceRequest.applyHasServiceId();
        marketPriceRequest.serviceId(srcDirInfo.serviceId());
        marketPriceRequest.applyHasPriority();
        marketPriceRequest.priority(1, 1);
		if (srcDirInfo.info().qosList().size() > 0)
		{
			marketPriceRequest.applyHasQos();
			marketPriceRequest.qos().dynamic(false);
			marketPriceRequest.qos().timeInfo(srcDirInfo.info().qosList().get(0).timeInfo());
			marketPriceRequest.qos().timeliness(srcDirInfo.info().qosList().get(0)
					.timeliness());
			marketPriceRequest.qos().rateInfo(srcDirInfo.info().qosList().get(0).rateInfo());
			marketPriceRequest.qos().rate(srcDirInfo.info().qosList().get(0).rate());
		}
        if (isPrivateStream)
            marketPriceRequest.applyPrivateStream();

        if (loginInfo.checkHasFeatures() && 
            loginInfo.features().checkHasSupportViewRequests() && 
            loginInfo.features().supportViewRequests() == 1 &&
            viewRequested)
        {
            marketPriceRequest.applyHasView();
            marketPriceRequest.viewFields().addAll(viewFieldList);
        }
    }

    //sends items as batch request
    private int sendBatchRequest(ReactorChannel chnl, List<String> itemNames, ReactorErrorInfo errorInfo)
    {
        int batchStreamId = watchList.add(domainType, "BATCH_" + new Date(), marketPriceRequest.checkPrivateStream());
        marketPriceRequest.streamId(batchStreamId);
        for (String itemName : itemNames)
        {
            watchList.add(domainType, itemName, marketPriceRequest.checkPrivateStream());
            marketPriceRequest.itemNames().add(itemName);
        }

        return encodeAndSendRequest(chnl, marketPriceRequest, errorInfo);
    }

    //sends one item at a time
    private int sendRequest(ReactorChannel chnl, List<String> itemNames, ReactorErrorInfo errorInfo)
    {
        int ret = CodecReturnCodes.SUCCESS;
        for (String itemName : itemNames)
        {
            Integer streamId = watchList.add(domainType, itemName, marketPriceRequest.checkPrivateStream());

            marketPriceRequest.itemNames().clear();
            marketPriceRequest.itemNames().add(itemName);

            marketPriceRequest.streamId(streamId);
            ret = encodeAndSendRequest(chnl, marketPriceRequest, errorInfo);
            if (ret < CodecReturnCodes.SUCCESS)
                return ret;
        }

        return CodecReturnCodes.SUCCESS;
    }

    private int encodeAndSendRequest(ReactorChannel chnl, MarketPriceRequest marketPriceRequest,
    		ReactorErrorInfo errorInfo)
    {
        //get a buffer for the item request
        TransportBuffer msgBuf = chnl.getBuffer(TRANSPORT_BUFFER_SIZE_REQUEST, false, errorInfo);

        if (msgBuf == null)
        {
            return CodecReturnCodes.FAILURE;
        }

        encIter.clear();
        encIter.setBufferAndRWFVersion(msgBuf, chnl.majorVersion(), chnl.minorVersion());

        int ret = marketPriceRequest.encode(encIter);
        if (ret < CodecReturnCodes.SUCCESS)
        {
            errorInfo.error().text("MarketPriceRequest.encode() failed");
            errorInfo.error().errorId(ret);
            return ret;
        }

        System.out.println(marketPriceRequest.toString());
        return chnl.submit(msgBuf, submitOptions, errorInfo);
    }

    /*
     * Processes a market price response. This consists of extracting the key,
     * printing out the item name contained in the key, decoding the field list
     * and field entry, and calling decodeFieldEntry() to decode the field entry
     * data.
     */
    int processResponse(Msg msg, DecodeIterator dIter, DataDictionary dictionary, CacheInfo cacheInfo, ReactorErrorInfo errorInfo)
    {
        switch (msg.msgClass())
        {
            case MsgClasses.REFRESH:
                return handleRefresh(msg, dIter, dictionary, cacheInfo, errorInfo);
            case MsgClasses.UPDATE:
                return handleUpdate(msg, dIter, dictionary, cacheInfo);
            case MsgClasses.STATUS:
                return handleStatus(msg, errorInfo);
            case MsgClasses.ACK:
                return handleAck(msg);
            default:
                System.out.println("Received Unhandled Item Msg Class: " + msg.msgClass());
                break;
        }

        return CodecReturnCodes.SUCCESS;
    }

    protected int handleAck(Msg msg)
    {
        System.out.println("Received AckMsg for stream " + msg.streamId());

        StringBuilder fieldValue = new StringBuilder();
        getItemName(msg, fieldValue);

        AckMsg ackMsg = (AckMsg)msg;

        fieldValue.append("\tackId=" + ackMsg.ackId() + "\n");
        if (ackMsg.checkHasSeqNum())
            fieldValue.append("\tseqNum=" + ackMsg.seqNum() + "\n");
        if (ackMsg.checkHasNakCode())
            fieldValue.append("\tnakCode=" + ackMsg.nakCode() + "\n");
        if (ackMsg.checkHasText())
            fieldValue.append("\ttext=" + ackMsg.text().toString());

        System.out.println(fieldValue.toString());
        return CodecReturnCodes.SUCCESS;
    }

    protected void getItemName(Msg msg, StringBuilder fieldValue)
    {
        // get key
        MsgKey key = msg.msgKey();

        // print out item name from key if it has it
        if (key != null && key.checkHasName())
        {
            if (msg.msgClass() == MsgClasses.REFRESH)
            {
                RefreshMsg refreshMsg = (RefreshMsg)msg;
                fieldValue.append(key.name().toString() + (refreshMsg.checkPrivateStream() ? " (PRIVATE STREAM)" : "") + "\nDOMAIN: " +
                        DomainTypes.toString(msg.domainType()) + "\n");
            }
            else
            {
                fieldValue.append(key.name().toString() + "\nDOMAIN: " +
                        DomainTypes.toString(msg.domainType()) + "\n");
                if (msg.msgClass() == MsgClasses.UPDATE)
                {   
                    fieldValue.append("UPDATE TYPE: " + UpdateEventTypes.toString(((UpdateMsg)msg).updateType()) + "\n");   
                }
            }

        }
        else
        // cached item name
        {
            WatchListEntry wle = watchList.get(msg.streamId());

            if (wle != null)
            {
                fieldValue.append(wle.itemName + (wle.isPrivateStream ? " (PRIVATE STREAM)" : " ") + "\nDOMAIN: " + DomainTypes.toString(msg.domainType()) + "\n");
                if (msg.msgClass() == MsgClasses.UPDATE)
                {
                    fieldValue.append("UPDATE TYPE: " + UpdateEventTypes.toString(((UpdateMsg)msg).updateType()) + "\n");
                }
            }
            else
            {
                // check if this is login stream for offstream posting
                if (msg.streamId() == loginStreamId)
                {
                    fieldValue.append("OFFPOST " + "\nDOMAIN: " + DomainTypes.toString(msg.domainType()) + "\n");
                }
            }
        }
    }

    protected int handleStatus(Msg msg, ReactorErrorInfo errorInfo)
    {
        StatusMsg statusMsg = (StatusMsg)msg;
        System.out.println("Received Item StatusMsg for stream " + msg.streamId());
        if (!statusMsg.checkHasState())
            return CodecReturnCodes.SUCCESS;

        // get state information
        State state = statusMsg.state();
        System.out.println("	" + state);

        WatchListEntry wle = watchList.get(msg.streamId());
        if (wle != null)
        {
        /* update our state table with the new state */
        if (!statusMsg.checkPrivateStream()) /* non-private stream */
        {
            /*
             * check if this response should be on private stream but is not
             */
            if (!statusMsg.checkPrivateStream()) /* non-private stream */
            {
                /*
                 * check if this response should be on private stream but is not
                 * batch responses for private stream may be sent on non-private
                 * stream
                 */
                /* if this is the case, close the stream */
                if (wle.isPrivateStream && !wle.itemName.contains("BATCH_"))
                {
                    System.out.println("Received non-private response for stream " +
                            msg.streamId() + " that should be private - closing stream");
                    // close stream
                    closeStream(redirectChnl, msg.streamId(), errorInfo);
                    // remove private stream entry from list
                    removeMarketPriceItemEntry(msg.streamId());
                    return CodecReturnCodes.FAILURE;
                }
            }
        }
        wle.itemState.dataState(statusMsg.state().dataState());
        wle.itemState.streamState(statusMsg.state().streamState());
        }

        // redirect to private stream if indicated
        if (statusMsg.state().streamState() == StreamStates.REDIRECTED
                && (statusMsg.checkPrivateStream()))
        {
            int ret = redirectToPrivateStream(msg.streamId(), errorInfo);
            if (ret != CodecReturnCodes.SUCCESS)
            {
                return ret;
            }
        }

        return CodecReturnCodes.SUCCESS;
    }

    protected int handleUpdate(Msg msg, DecodeIterator dIter, DataDictionary dictionary, CacheInfo cacheInfo)
    {
        UpdateMsg updateMsg = (UpdateMsg)msg;
        PostUserInfo pu = updateMsg.postUserInfo();
        if ( pu != null)
        {
        	System.out.println(" Received UpdateMsg for stream " + updateMsg.streamId() + " from publisher with user ID: " + pu.userId() + " at user address: " + pu.userAddrToString(pu.userAddr()));
        }
        
        if (cacheInfo.useCache)
    	{
    		int ret = CacheHandler.applyMsgToCache(dIter, watchList.get(msg.streamId()).cacheEntry, cacheInfo, msg);
    		if (ret != CodecReturnCodes.SUCCESS)
    			System.out.println(" Error Applying payload to cache : " + cacheInfo.cacheError.text());
    		else
    			System.out.println("Payload cached");
    		return ret;
    	}
    	else
    		return decode(msg, dIter, dictionary);
    }

    protected int decode(Msg msg, DecodeIterator dIter, DataDictionary dictionary)
    {
        StringBuilder fieldValue = new StringBuilder();
        getItemName(msg, fieldValue);
        if (msg.msgClass() == MsgClasses.REFRESH)
            fieldValue.append((((RefreshMsg)msg).state()).toString() + "\n");
        int result = decodePayload(dIter, dictionary, fieldValue);
        //print rmtes fields(page data) only
        for (Integer fieldId  : pageMap.keySet())
        {
           System.out.println(pageMap.get(fieldId));
        }
        //print all fields including page data
       /* for (Integer fieldId  : allFieldsMap.keySet())
        {
           System.out.println(allFieldsMap.get(fieldId));
        }*/
        return result;
    }
    
    public int decodePayload(DecodeIterator dIter, DataDictionary dictionary, StringBuilder fieldValue)
    {
    	int ret = fieldList.decode(dIter, null);
        if (ret != CodecReturnCodes.SUCCESS)
        {
            System.out.println("DecodeFieldList() failed with return code: " + ret);
            return ret;
        }

        // decode each field entry in list
        while ((ret = fieldEntry.decode(dIter)) != CodecReturnCodes.END_OF_CONTAINER)
        {   
            if (ret != CodecReturnCodes.SUCCESS)
            {
                System.out.println("decodeFieldEntry() failed with return code: " + ret);
                return ret;
            }
            //decode page data containing partial update
            ret = decodeFieldEntryPage(fieldEntry, dIter, dictionary, fieldValue);
            if (ret != CodecReturnCodes.SUCCESS)
            {
                System.out.println("decodeFieldEntry() failed");
                return ret;
            }
            fieldValue.append("\n");
        }
        //System.out.println(fieldValue.toString());
        return CodecReturnCodes.SUCCESS;
    }

    protected int handleRefresh(Msg msg, DecodeIterator dIter, DataDictionary dictionary, CacheInfo cacheInfo, ReactorErrorInfo errorInfo)
    {
        RefreshMsg refreshMsg = (RefreshMsg)msg; 
        PostUserInfo pu = refreshMsg.postUserInfo();
        if ( pu != null)
        {
        	System.out.println(" Received RefreshMsg for stream " + refreshMsg.streamId() + " from publisher with user ID: " + pu.userId() + " at user address: " + pu.userAddrToString(pu.userAddr()));
        }        

        WatchListEntry wle = watchList.get(msg.streamId());

        /* check if this response should be on private stream but is not */
        /* if this is the case, close the stream */
        if (!refreshMsg.checkPrivateStream() && wle.isPrivateStream)
        {
            System.out.println("Received non-private response for stream " + msg.streamId() +
                    " that should be private - closing stream");
            // close stream
            closeStream(redirectChnl, msg.streamId(), errorInfo);

            // remove private stream entry from list
            removeMarketPriceItemEntry(msg.streamId());
            errorInfo.error().text("Received non-private response for stream " + msg.streamId() +
                    " that should be private - closing stream");
            return CodecReturnCodes.FAILURE;
        }
        /*
         * update our item state list if its a refresh, then process just like
         * update
         */
        wle.itemState.dataState(refreshMsg.state().dataState());
        wle.itemState.streamState(refreshMsg.state().streamState());

    	if (cacheInfo.useCache)
    	{
        	if (wle.cacheEntry == null)
        		wle.cacheEntry = CacheHandler.createCacheEntry(cacheInfo);
        	
    		int ret = CacheHandler.applyMsgToCache(dIter, wle.cacheEntry, cacheInfo, msg);
    		if (ret != CodecReturnCodes.SUCCESS)
    			errorInfo.error().text(" Error Applying payload to cache : " + cacheInfo.cacheError.text());
    		else
    			System.out.println("Payload cached");
    		
    		return ret;
    	}
    	else
    		return this.decode(msg, dIter, dictionary);
    }

    /*
     * This is used by all market price domain handlers to output field lists.
     * 
     * Decodes the field entry data and prints out the field entry data with
     * help of the dictionary. Returns success if decoding succeeds or failure
     * if decoding fails.
     */
    protected int decodeFieldEntry(FieldEntry fEntry, DecodeIterator dIter,
            DataDictionary dictionary, StringBuilder fieldValue)
    {
        // get dictionary entry
        DictionaryEntry dictionaryEntry = dictionary.entry(fEntry.fieldId());

        // return if no entry found
        if (dictionaryEntry == null)
        {
            fieldValue.append("\tFid " + fEntry.fieldId() + " not found in dictionary");
            return CodecReturnCodes.SUCCESS;
        }

        // print out fid name
        fieldValue.append("\t" + fEntry.fieldId() + "/" + dictionaryEntry.acronym().toString() + ": ");

        // decode and print out fid value
        int dataType = dictionaryEntry.rwfType();
        int ret = 0;
        switch (dataType)
        {
            case DataTypes.UINT:
                ret = fidUIntValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    fieldValue.append(fidUIntValue.toLong());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeUInt() failed: <" + CodecReturnCodes.toString(ret) + ">");
                    return ret;
                }
                break;
            case DataTypes.INT:
                ret = fidIntValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    fieldValue.append(fidIntValue.toLong());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeInt() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.FLOAT:
                ret = fidFloatValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    fieldValue.append(fidFloatValue.toFloat());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeFloat() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.DOUBLE:
                ret = fidDoubleValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    fieldValue.append(fidDoubleValue.toDouble());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeDouble() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.REAL:
                ret = fidRealValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    fieldValue.append(fidRealValue.toDouble());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeReal() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.ENUM:
                ret = fidEnumValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    EnumType enumType = dictionary.entryEnumType(dictionaryEntry,
                                                                 fidEnumValue);

                    if (enumType == null)
                    {
                        fieldValue.append(fidEnumValue.toInt());
                    }
                    else
                    {
                        fieldValue.append(enumType.display().toString() + "(" +
                                fidEnumValue.toInt() + ")");
                    }
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeEnum() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.DATE:
                ret = fidDateValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    fieldValue.append(fidDateValue.toString());

                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeDate() failed: <" +
                            CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.TIME:
                ret = fidTimeValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    fieldValue.append(fidTimeValue.toString());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeTime() failed: <" +
                            CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.DATETIME:
                ret = fidDateTimeValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    fieldValue.append(fidDateTimeValue.toString());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeDateTime() failed: <" + CodecReturnCodes.toString(ret) + ">");
                    return ret;
                }
                break;
            case DataTypes.QOS:
                ret = fidQosValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    fieldValue.append(fidQosValue.toString());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeQos() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.STATE:
                ret = fidStateValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    fieldValue.append(fidStateValue.toString());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeState() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            // For an example of array decoding, see
            // FieldListCodec.exampleDecode()
            case DataTypes.ARRAY:
                break;
            case DataTypes.BUFFER:
            case DataTypes.ASCII_STRING:
            case DataTypes.UTF8_STRING:
            case DataTypes.RMTES_STRING:
                if (fEntry.encodedData().length() > 0)
                {
                    fieldValue.append(fEntry.encodedData().toString());
                }
                else
                {
                    ret = CodecReturnCodes.BLANK_DATA;
                }
                break;
            default:
                fieldValue.append("Unsupported data type (" + DataTypes.toString(dataType) + ")");
                break;
        }
        if (ret == CodecReturnCodes.BLANK_DATA)
        {
            fieldValue.append("<blank data>");
        }

        return CodecReturnCodes.SUCCESS;
    }
    protected int decodeFieldEntryPage(FieldEntry fEntry, DecodeIterator dIter,
            DataDictionary dictionary, StringBuilder fieldValue)
    {
        // get dictionary entry
        DictionaryEntry dictionaryEntry = dictionary.entry(fEntry.fieldId());

        // return if no entry found
        if (dictionaryEntry == null)
        {
            fieldValue.append("\tFid " + fEntry.fieldId() + " not found in dictionary");
            return CodecReturnCodes.SUCCESS;
        }

        // print out fid name
        String header = "\t" + fEntry.fieldId() + "/" + dictionaryEntry.acronym().toString() + ":"+"\t";
        // decode and print out fid value
        int dataType = dictionaryEntry.rwfType();
        int ret = 0;
        switch (dataType)
        {
            case DataTypes.UINT:
                ret = fidUIntValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                	allFieldsMap.put(fEntry.fieldId(), header + String.valueOf(fidUIntValue.toLong()));
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeUInt() failed: <" + CodecReturnCodes.toString(ret) + ">");
                    return ret;
                }
                break;
            case DataTypes.INT:
                ret = fidIntValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                	allFieldsMap.put(fEntry.fieldId(), header + String.valueOf(fidIntValue.toLong()));
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeInt() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.FLOAT:
                ret = fidFloatValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                	allFieldsMap.put(fEntry.fieldId(), header + String.valueOf(fidFloatValue.toFloat()));
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeFloat() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.DOUBLE:
                ret = fidDoubleValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                	allFieldsMap.put(fEntry.fieldId(), header + String.valueOf(fidDoubleValue.toDouble()));
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeDouble() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.REAL:
                ret = fidRealValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                	allFieldsMap.put(fEntry.fieldId(), header + String.valueOf(fidRealValue.toDouble()));
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeReal() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.ENUM:
                ret = fidEnumValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                    EnumType enumType = dictionary.entryEnumType(dictionaryEntry,
                                                                 fidEnumValue);

                    if (enumType == null)
                    {
                    	allFieldsMap.put(fEntry.fieldId(), header + String.valueOf(fidEnumValue.toInt()));
                    }
                    else
                    {
                    	String enumValue = enumType.display().toString() + "(" +
                                fidEnumValue.toInt() + ")";
                    	allFieldsMap.put(fEntry.fieldId(), header + enumValue);
                    }
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeEnum() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.DATE:
                ret = fidDateValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                	allFieldsMap.put(fEntry.fieldId(),header + fidDateValue.toString());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeDate() failed: <" +
                            CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.TIME:
                ret = fidTimeValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                	allFieldsMap.put(fEntry.fieldId(),header + fidTimeValue.toString());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeTime() failed: <" +
                            CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.DATETIME:
                ret = fidDateTimeValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                	allFieldsMap.put(fEntry.fieldId(),header + fidDateTimeValue.toString());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeDateTime() failed: <" + CodecReturnCodes.toString(ret) + ">");
                    return ret;
                }
                break;
            case DataTypes.QOS:
                ret = fidQosValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                	allFieldsMap.put(fEntry.fieldId(),header + fidQosValue.toString());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeQos() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            case DataTypes.STATE:
                ret = fidStateValue.decode(dIter);
                if (ret == CodecReturnCodes.SUCCESS)
                {
                	allFieldsMap.put(fEntry.fieldId(),header + fidStateValue.toString());
                }
                else if (ret != CodecReturnCodes.BLANK_DATA)
                {
                    System.out.println("DecodeState() failed: <" + CodecReturnCodes.toString(ret) + ">");

                    return ret;
                }
                break;
            // For an example of array decoding, see
            // FieldListCodec.exampleDecode()
            case DataTypes.ARRAY:
                break;
            case DataTypes.BUFFER:
            case DataTypes.ASCII_STRING:
            case DataTypes.UTF8_STRING:
            	if (fEntry.encodedData().length() > 0)
                {
            		allFieldsMap.put(fEntry.fieldId(),header + fEntry.encodedData().toString());
                }
                else
                {
                    ret = CodecReturnCodes.BLANK_DATA;
                }
                break;
            case DataTypes.RMTES_STRING:
            	//page 64x14 or 80x25
            	if( (fEntry.fieldId() >= 215 && fEntry.fieldId() <= 228) || 
        				(fEntry.fieldId() >= 315 && fEntry.fieldId() <= 339)) {
            		String fullUpdated = apply(fEntry.fieldId(), fEntry.encodedData().toString());
                	allFieldsMap.put(fEntry.fieldId(),header + fullUpdated);
            	}
            	else if (fEntry.encodedData().length() > 0)
                {
            		allFieldsMap.put(fEntry.fieldId(),header + fEntry.encodedData().toString());
                }
                else
                {
                    ret = CodecReturnCodes.BLANK_DATA;
                }
                break;
            default:
            	allFieldsMap.put(fEntry.fieldId(),header + "Unsupported data type (" + DataTypes.toString(dataType) + ")");
                break;
        }
       
        if (ret == CodecReturnCodes.BLANK_DATA)
        {
        	allFieldsMap.put(fEntry.fieldId(),header + "<blank data>");
        }
        
        return CodecReturnCodes.SUCCESS;
    }
    //pimchaya
    //convert String to Hex. 
    private static String stringtoHexString(String str) {
        char[] chars = str.toCharArray();
        StringBuilder hex = new StringBuilder();
        for (char ch : chars) {
            hex.append(Integer.toHexString((int) ch));
        }
        return hex.toString();
    }
    //Convert Hex to String
    private static String hexStringtoString(String hexStr) {
		byte[] s = DatatypeConverter.parseHexBinary(hexStr);
		return new String(s);
	}
    //The syntax of an intra-field positioning sequence
    //<CSI>n<HPA> 
    //<CSI> is Hex 1b5b or 9b
    //n	an ASCII numeric 
    //<HPA> is Hex 60
    private String apply(int fieldId, String update) {
    	//Create String to Hex. Assume the update variable is:
    	//(0x1b)[33`1291  63(0x1b)[47`6	
    	//hexStr will be 
    	//1b5b33336031323931202036331b5b34376036
    	String hexStr = stringtoHexString(update);
    	String start;
    	//if string starts with <CSI> which can be "1b5b" or "9b", interpret intra-field positioning sequences. 
		if(hexStr.startsWith("1b5b") || hexStr.startsWith("9b")) {
			//set <CSI> which can be 1b5b 
			if(hexStr.startsWith("1b5b"))
				start = "1b5b";
			//or 9b 
			else
				start = "9b";
			//find all intra-field positioning sequences in the field
			//Remove the first <CSI> to split(next function) properly
			hexStr = hexStr.substring(start.length());
			//hexStr will be 33336031323931202036331b5b34376036
			//Call split function to create an array with substrings of Hex divided at occurrence of <CSI>.
			//<CSI> is not included in the result.
			//There are 2 intra-field positioning sequences after splitting: 1) 3333603132393120203633 2) 34376036
			String[] intraFieldArray = hexStr.split(start);
			
			//Create a map of intra-field positioning sequences. Key is number position, value is string changed value
			TreeMap <Integer, String> intraFieldMap = new TreeMap <Integer, String>();
			//Syntax is position<HPA>changedValue; <HPA> is Hex 60
			for(String anIntraField : intraFieldArray) {
				String[] tmp = anIntraField.split("60");
				//change Hex position to number
				Integer position = Integer.valueOf(hexStringtoString(tmp[0]));
				//change Hex changed value to String change value
				String value = hexStringtoString(tmp[1]);
				//put number position and changed value in intraFieldMap
				intraFieldMap.put(position, value);
			}
			//There are 2 key-value mappings in intraFieldMap
			//1) key = 33, value = "1291  63"
			//2) key = 47, value = "6"
			
			//apply each value in the intraFieldMap on the current value based on key position.
			//assume the current value is 	" 6387   -1s  6698  4975 |  1453  4409  2087  9054"  
			//replace the row at offset 33 with "1291  63"
			//replace the row at offset 47 with "6"
			//the other characters are not changed
			//The result is					" 6387   -1s  6698  4975 |  1453  1291  6387  9064"
			String currentRow = pageMap.get(fieldId);
			StringBuilder fullUpdate = new StringBuilder("");
			int beginIndex=0;
			int maxPosition = currentRow.length()-1;
			for (Integer position : intraFieldMap.keySet())
			{
				String updatedStr = intraFieldMap.get(position);
				fullUpdate.append(currentRow.substring(beginIndex, position)); 
				fullUpdate.append(updatedStr);
				beginIndex = position+updatedStr.length();
			}
			if(beginIndex <=  maxPosition)
				fullUpdate.append(currentRow.substring(beginIndex)); 
			
			//put key is field id and value which applied all intra-field positioning sequences  
			pageMap.put(fieldId, fullUpdate.toString());
		}
    	//full update, replace the current value with the changed value
		else {
			pageMap.put(fieldId, update);
		}
		//return the full changed value
    	return pageMap.get(fieldId);
    }

    /*
     * Close all item streams.
     */
    int closeStreams(ReactorChannel chnl, ReactorErrorInfo errorInfo)
    {
        int ret = 0;

        Iterator<Map.Entry<StreamIdKey, WatchListEntry>> iter = watchList.iterator();
        while (iter.hasNext())
        {
            Map.Entry<StreamIdKey, WatchListEntry> entry = iter.next();
            /*
             * we only want to close a stream if it was not already closed (e.g.
             * rejected by provider, closed via refresh or status, or
             * redirected)
             */
            if (entry.getValue().itemState.isFinal())
                continue;
            if (entry.getValue().domainType == domainType)
            {
                ret = closeStream(chnl, entry.getKey().streamId(), errorInfo);
                if (ret != CodecReturnCodes.SUCCESS)
                {
                    return ret;
                }
                iter.remove();
            }
        }

        return CodecReturnCodes.SUCCESS;
    }

    /*
     * Redirect a request to a private stream.
     */
    private int redirectToPrivateStream(int streamId, ReactorErrorInfo errorInfo)
    {
        WatchListEntry wle = watchList.get(streamId);

        /* remove non-private stream entry from list */
        removeMarketPriceItemEntry(streamId);

        /* add item name to private stream list */
        Integer psStreamId = watchList.add(domainType, wle.itemName, true);

        generateRequest(marketPriceRequest, true, redirectSrcDirInfo, redirectLoginInfo);
        marketPriceRequest.itemNames().add(wle.itemName);
        marketPriceRequest.streamId(psStreamId);
        return encodeAndSendRequest(redirectChnl, marketPriceRequest, errorInfo);
    }
    
    void loginStreamId(int streamId)
    {
    	loginStreamId = streamId;
    }
}
