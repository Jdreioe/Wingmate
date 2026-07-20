#!/usr/bin/env python3
"""Create an ORT MatMulNBits INT4 Chatterbox conditional decoder."""

from __future__ import annotations

import argparse
import hashlib
import logging
from pathlib import Path

import onnx
from onnxruntime.quantization import quant_utils
from onnxruntime.quantization.matmul_nbits_quantizer import (
    DefaultWeightOnlyQuantConfig,
    MatMulNBitsQuantizer,
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--block-size", type=int, default=128)
    args = parser.parse_args()
    logging.getLogger("onnxruntime.quantization").setLevel(logging.WARNING)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    model = quant_utils.load_model_with_shape_infer(args.input)
    before = sum(node.op_type == "MatMul" for node in model.graph.node)
    config = DefaultWeightOnlyQuantConfig(
        block_size=args.block_size,
        is_symmetric=True,
        accuracy_level=4,
        quant_format=quant_utils.QuantFormat.QOperator,
        op_types_to_quantize=("MatMul",),
        quant_axes=(("MatMul", 0),),
    )
    quantizer = MatMulNBitsQuantizer(model, algo_config=config)
    quantizer.process()
    quantizer.model.save_model_to_file(args.output.as_posix(), use_external_data_format=True)

    graph = onnx.load(args.output, load_external_data=False)
    after = sum(node.op_type == "MatMulNBits" for node in graph.graph.node)
    files = [args.output, *sorted(args.output.parent.glob(f"{args.output.name}*"))]
    unique_files = list(dict.fromkeys(path for path in files if path.is_file()))
    print(f"Quantized {after}/{before} MatMul nodes")
    for path in unique_files:
        print(f"{path.name}\t{path.stat().st_size}\t{sha256(path)}")


if __name__ == "__main__":
    main()
