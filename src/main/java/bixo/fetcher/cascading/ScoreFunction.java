package bixo.fetcher.cascading;

import java.io.IOException;

import bixo.IConstants;
import bixo.cascading.NullContext;
import bixo.fetcher.util.IScoreGenerator;
import bixo.tuple.GroupedUrlDatum;
import cascading.flow.FlowProcess;
import cascading.operation.BaseOperation;
import cascading.operation.Function;
import cascading.operation.FunctionCall;
import cascading.tuple.Fields;
import cascading.tuple.Tuple;

@SuppressWarnings("serial")
public class ScoreFunction extends BaseOperation<NullContext> implements Function<NullContext> {

    private final IScoreGenerator _scoreGenerator;
    private final Fields _metaDataFieldNames;

    public ScoreFunction(IScoreGenerator scoreGenerator, Fields metaDataFieldNames) {
        super(new Fields(IConstants.SCORE));
        _scoreGenerator = scoreGenerator;
        _metaDataFieldNames = metaDataFieldNames;
    }

    @Override
    public void operate(FlowProcess process, FunctionCall<NullContext> funCall) {
        double generatedScore;
        GroupedUrlDatum groupedUrl = GroupedUrlDatum.fromTuple(funCall.getArguments().getTuple(), _metaDataFieldNames);

        try {
            generatedScore = _scoreGenerator.generateScore(groupedUrl);
            funCall.getOutputCollector().add(new Tuple(generatedScore));
        } catch (IOException e) {
            // we throw the exception here to get this data into the trap
            throw new RuntimeException("Unable to generate score for: " + groupedUrl, e);
        }
    }
}
