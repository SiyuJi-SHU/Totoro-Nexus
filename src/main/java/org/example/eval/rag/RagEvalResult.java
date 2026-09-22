package org.example.eval.rag;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class RagEvalResult {
    private String id;
    private String question;
    private String expectedSourceFile;
    private List<String> retrievedFiles = new ArrayList<>();
    private List<String> matchedKeywords = new ArrayList<>();
    private int hitRank = -1;
    private double keywordHitRate;
    private long latencyMs;
    private String errorMessage;

    public boolean isRecallAt1() {
        return hitRank == 1;
    }

    public boolean isRecallAtK() {
        return hitRank > 0;
    }
}
