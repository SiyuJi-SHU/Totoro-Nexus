package org.example.eval.rag;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class RagEvalCase {
    private String id;
    private String question;
    private String expectedSourceFile;
    private List<String> expectedKeywords = new ArrayList<>();
    private String difficulty;
    private int topK = 3;
    private String origin;
}
