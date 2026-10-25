//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.onnx;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxMap;
import ai.onnxruntime.OnnxSequence;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.math.BigDecimal;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vn.io.dxa.openframl.gate.ProfileSnapshot;
import vn.io.dxa.openframl.gate.Score;
import vn.io.dxa.openframl.gate.ScoreProvider;
import vn.io.dxa.openframl.gate.VelocitySnapshot;
import vn.io.dxa.openframl.model.TransactionEvent;

/**
 * Scores payments with an ONNX model, in the gate's process.
 *
 * <p>The model carries its own contract in its metadata, so a model file is everything needed to deploy it:
 * <ul>
 *   <li>{@value #FEATURES}: the {@linkplain ScoreFeatures feature names} it was trained on, comma-separated, in the
 *       order of its input;</li>
 *   <li>{@value #VERSION}: the version recorded with every decision, e.g. {@code fraud-xgb-3};</li>
 *   <li>{@value #OUTPUT} (optional): the output holding the fraud probability; by default the last output.</li>
 * </ul>
 *
 * <p>The input is one float tensor of shape {@code [1, features]}. The output is the probability of fraud: a float
 * tensor ({@code [1]}, {@code [1, 1]}, or {@code [1, 2]} with the fraud class last), or the list of class-to-probability
 * maps that scikit-learn exports, from which class {@code 1} is read. The model is checked when it is loaded, not on
 * the first payment.
 *
 * <p>Each call runs on the caller's thread with one ONNX thread, because the gate scores many payments at once:
 * parallelism comes from the calls, not from inside one.
 */
public final class OnnxScoreProvider implements ScoreProvider, AutoCloseable {

    /** Metadata key: the feature names, comma-separated. */
    public static final String FEATURES = "openframl.features";
    /** Metadata key: the model version. */
    public static final String VERSION = "openframl.model_version";
    /** Metadata key: the output to read. */
    public static final String OUTPUT = "openframl.output";

    private static final OrtEnvironment ENV = OrtEnvironment.getEnvironment();

    private final OrtSession session;
    private final String modelVersion;
    private final List<String> featureNames;
    private final List<ScoreFeatures.Feature> features;
    private final String input;
    private final String output;
    private final ZoneId zone;

    private OnnxScoreProvider(OrtSession session, ZoneId zone) throws OrtException {
        this.session = session;
        this.zone = Objects.requireNonNull(zone, "zone");
        Map<String, String> metadata = session.getMetadata().getCustomMetadata();
        this.modelVersion = required(metadata, VERSION);
        this.featureNames = Arrays.stream(required(metadata, FEATURES).split(",")).map(String::strip).toList();
        this.features = ScoreFeatures.resolve(featureNames);
        if (session.getNumInputs() != 1) {
            throw new IllegalArgumentException("model must have one input, has " + session.getInputNames());
        }
        this.input = session.getInputNames().iterator().next();
        long[] shape = ((TensorInfo) session.getInputInfo().get(input).getInfo()).getShape();
        if (shape.length != 2 || (shape[1] >= 0 && shape[1] != features.size())) {
            throw new IllegalArgumentException("model input " + input + " has shape " + Arrays.toString(shape)
                    + "; expected [1, " + features.size() + "] for " + featureNames);
        }
        Map<String, NodeInfo> outputs = session.getOutputInfo();
        this.output = metadata.getOrDefault(OUTPUT, List.copyOf(outputs.keySet()).getLast());
        if (!outputs.containsKey(output)) {
            throw new IllegalArgumentException("model has no output " + output + "; outputs: " + outputs.keySet());
        }
    }

    /**
     * Loads the model at {@code path}.
     *
     * @param zone the bank's business time zone, for features such as the hour of day; usually
     *             {@code CountryPack.businessZone()}
     * @throws IllegalArgumentException if the model's metadata or shape does not fit the contract above
     */
    public static OnnxScoreProvider load(Path path, ZoneId zone) throws OrtException {
        return open(ENV.createSession(path.toString(), options()), zone);
    }

    /** Loads a model from its bytes. */
    public static OnnxScoreProvider load(byte[] model, ZoneId zone) throws OrtException {
        return open(ENV.createSession(model, options()), zone);
    }

    private static OnnxScoreProvider open(OrtSession session, ZoneId zone) throws OrtException {
        try {
            OnnxScoreProvider provider = new OnnxScoreProvider(session, zone);
            provider.check();
            return provider;
        } catch (OrtException | RuntimeException e) {
            session.close();
            throw e;
        }
    }

    private static OrtSession.SessionOptions options() throws OrtException {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(1);
        options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        return options;
    }

    /** Runs the model once on zeros, so a model whose output does not fit fails now rather than in production. */
    private void check() throws OrtException {
        run(new float[features.size()]);
    }

    @Override
    public Score score(TransactionEvent event, ProfileSnapshot profiles, VelocitySnapshot velocity) {
        ScoreFeatures.Inputs inputs = new ScoreFeatures.Inputs(event, profiles, velocity, zone);
        float[] vector = new float[features.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) features.get(i).value(inputs);
        }
        try {
            return new Score(modelVersion, new BigDecimal(Float.toString(run(vector))));
        } catch (OrtException e) {
            throw new IllegalStateException("model " + modelVersion + " failed", e);
        }
    }

    private float run(float[] vector) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(ENV, FloatBuffer.wrap(vector), new long[] {1, vector.length});
                OrtSession.Result result = session.run(Map.of(input, tensor), Set.of(output))) {
            return probability(result.get(output).orElseThrow());
        }
    }

    private float probability(OnnxValue value) throws OrtException {
        Object content = value.getValue();
        if (value instanceof OnnxTensor && content instanceof float[][] rows && rows.length == 1 && rows[0].length > 0) {
            return rows[0][rows[0].length - 1];
        }
        if (value instanceof OnnxTensor && content instanceof float[] row && row.length == 1) {
            return row[0];
        }
        if (value instanceof OnnxSequence sequence && !sequence.getValue().isEmpty()
                && sequence.getValue().getFirst() instanceof OnnxMap map) {
            Object probability = map.getValue().get(1L);
            if (probability instanceof Float fraud) {
                return fraud;
            }
        }
        throw new IllegalStateException("model " + modelVersion + ": output " + output + " is not a fraud probability: "
                + value.getInfo());
    }

    public String modelVersion() {
        return modelVersion;
    }

    public List<String> featureNames() {
        return featureNames;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }

    private static String required(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("model metadata has no " + key);
        }
        return value;
    }
}
