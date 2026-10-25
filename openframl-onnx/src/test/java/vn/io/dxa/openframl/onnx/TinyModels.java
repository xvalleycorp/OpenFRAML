//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.onnx;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Writes small ONNX models without the ONNX tooling, field by field from onnx.proto: a logistic regression,
 * {@code sigmoid(features × weights + bias)}, with the given metadata.
 */
final class TinyModels {

    private static final int FLOAT = 1;

    private TinyModels() {}

    /**
     * @param outputColumns 1 for a {@code [1, 1]} fraud probability, 2 for {@code [1, 2]} with the fraud class last
     */
    static byte[] logistic(float[] weights, float bias, int outputColumns, Map<String, String> metadata) {
        int n = weights.length;
        float[] matrix = new float[n * outputColumns];
        float[] biases = new float[outputColumns];
        for (int i = 0; i < n; i++) {
            matrix[i * outputColumns + outputColumns - 1] = weights[i];
        }
        biases[outputColumns - 1] = bias;
        if (outputColumns == 2) {
            // Class 0 gets the opposite logit; only the last column is read
            for (int i = 0; i < n; i++) {
                matrix[i * 2] = -weights[i];
            }
            biases[0] = -bias;
        }

        byte[] graph = message(out -> {
            bytes(out, 1, node(List.of("features", "W"), "logit", "MatMul"));
            bytes(out, 1, node(List.of("logit", "B"), "shifted", "Add"));
            bytes(out, 1, node(List.of("shifted"), "fraud", "Sigmoid"));
            out.writeString(2, "tiny-logistic");
            bytes(out, 5, tensor("W", new long[] {n, outputColumns}, matrix));
            bytes(out, 5, tensor("B", new long[] {outputColumns}, biases));
            bytes(out, 11, valueInfo("features", n));
            bytes(out, 12, valueInfo("fraud", outputColumns));
        });
        return message(out -> {
            out.writeInt64(1, 8);
            out.writeString(2, "openframl-test");
            bytes(out, 7, graph);
            bytes(out, 8, message(opset -> opset.writeInt64(2, 13)));
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                bytes(out, 14, message(prop -> {
                    prop.writeString(1, entry.getKey());
                    prop.writeString(2, entry.getValue());
                }));
            }
        });
    }

    private static byte[] node(List<String> inputs, String output, String op) {
        return message(out -> {
            for (String input : inputs) {
                out.writeString(1, input);
            }
            out.writeString(2, output);
            out.writeString(3, op + "_" + output);
            out.writeString(4, op);
        });
    }

    private static byte[] tensor(String name, long[] dims, float[] values) {
        return message(out -> {
            for (long dim : dims) {
                out.writeInt64(1, dim);
            }
            out.writeInt32(2, FLOAT);
            for (float value : values) {
                out.writeFloat(4, value);
            }
            out.writeString(8, name);
        });
    }

    /** A float tensor of shape {@code [batch, columns]}, batch symbolic. */
    private static byte[] valueInfo(String name, int columns) {
        byte[] shape = message(out -> {
            bytes(out, 1, message(dim -> dim.writeString(2, "batch")));
            bytes(out, 1, message(dim -> dim.writeInt64(1, columns)));
        });
        byte[] tensorType = message(out -> {
            out.writeInt32(1, FLOAT);
            bytes(out, 2, shape);
        });
        return message(out -> {
            out.writeString(1, name);
            bytes(out, 2, message(type -> bytes(type, 1, tensorType)));
        });
    }

    private interface Writer {
        void write(CodedOutputStream out) throws IOException;
    }

    private static byte[] message(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            writer.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void bytes(CodedOutputStream out, int field, byte[] value) throws IOException {
        out.writeBytes(field, ByteString.copyFrom(value));
    }
}
