package org.example.eval.rag;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class RagEvalRun {
    private RagEvalSummary summary;
    private List<RagEvalResult> results = new ArrayList<>();
}
