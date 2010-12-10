package bixo.operations;

import java.util.Iterator;

import org.apache.log4j.Logger;

import bixo.cascading.NullContext;
import bixo.cascading.PartitioningKey;
import bixo.config.BaseFetchJobPolicy;
import bixo.config.BaseFetchJobPolicy.FetchSetInfo;
import bixo.datum.FetchSetDatum;
import bixo.datum.ScoredUrlDatum;
import bixo.robots.BaseRobotRules;
import bixo.utils.GroupingKey;
import cascading.flow.FlowProcess;
import cascading.operation.BaseOperation;
import cascading.operation.Buffer;
import cascading.operation.BufferCall;
import cascading.tuple.TupleEntry;
import cascading.tuple.TupleEntryCollector;

/**
 * We get ScoredUrlDatums, grouped by server IP address.
 * 
 * We need to generate sets of URLs to fetch, using a kept-alive connection.
 * Our output thus is one or more FetchSetDatums.
 *
 */
@SuppressWarnings( { "serial", "unchecked" })
public class MakeFetchSetsBuffer extends BaseOperation<NullContext> implements Buffer<NullContext> {
    private static final Logger LOGGER = Logger.getLogger(MakeFetchSetsBuffer.class);

    private int _numReduceTasks;
    private BaseFetchJobPolicy _policy;
    
    public MakeFetchSetsBuffer(BaseFetchJobPolicy policy, int numReduceTasks) {
        super(FetchSetDatum.FIELDS);

        _policy = policy;
        _numReduceTasks = numReduceTasks;
    }

    @Override
    public void operate(FlowProcess process, BufferCall buffCall) {
        Iterator<TupleEntry> values = buffCall.getArgumentsIterator();
        TupleEntry group = buffCall.getGroup();

        // <key> is the output of the IGroupingKeyGenerator used. This should
        // be <IP address>-<crawl delay in ms>
        String key = group.getString(0);

        if (GroupingKey.isSpecialKey(key)) {
            throw new RuntimeException("Invalid grouping key: " + key);
        }

        long crawlDelay = GroupingKey.getCrawlDelayFromKey(key);
        if (crawlDelay == BaseRobotRules.UNSET_CRAWL_DELAY) {
            crawlDelay = _policy.getDefaultCrawlDelay();
        }
        
        _policy.startFetchSet(key, crawlDelay);
        
        TupleEntryCollector collector = buffCall.getOutputCollector();

        PartitioningKey newKey = new PartitioningKey(key, _numReduceTasks);
        
        while (values.hasNext()) {
            ScoredUrlDatum scoredDatum = new ScoredUrlDatum(new TupleEntry(values.next()));
            FetchSetInfo setInfo = _policy.nextFetchSet(scoredDatum);
            if (setInfo != null) {
                boolean hasNext = values.hasNext();
                FetchSetDatum result = makeFetchSetDatum(setInfo, newKey, hasNext);
                collector.add(result.getTuple());
                
                // Avoid bug in Cascading 1.2, where calling hasNext after it's returned false will
                // throw a NPE.
                if (!hasNext) {
                    break;
                }
            }
        }
        
        // See if we have another partially built datum to add.
        FetchSetInfo setInfo = _policy.endFetchSet();
        if (setInfo != null) {
            FetchSetDatum result = makeFetchSetDatum(setInfo, newKey, false);
            collector.add(result.getTuple());
        }
    }

    private FetchSetDatum makeFetchSetDatum(FetchSetInfo setInfo, PartitioningKey key, boolean hasNext) {
        LOGGER.trace(String.format("Added %d urls for ref %s in group %d at %d", setInfo.getUrls().size(), key.getRef(), key.getValue(), setInfo.getSortKey()));
        
        FetchSetDatum result = new FetchSetDatum(setInfo.getUrls(), setInfo.getSortKey(), setInfo.getFetchDelay(), key.getValue(), key.getRef());
        result.setLastList(!hasNext || setInfo.isSkipping());
        result.setSkipped(setInfo.isSkipping());
        return result;
    }
    

}
