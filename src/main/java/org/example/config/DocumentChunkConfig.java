package org.example.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 文档分片配置
 */
@Getter
@Configuration
@ConfigurationProperties(prefix = "document.chunk")
public class DocumentChunkConfig {
    
    /**
     * 每个分片的最大字符数
     */
    private int maxSize = 1200;
    
    /**
     * 分片之间的重叠字符数
     */
    private int overlap = 200;

    /**
     * 分片策略：size 表示标题优先并对超长章节继续切分；
     * heading 表示每个 Markdown 标题章节直接作为一个分片。
     */
    private String strategy = "size";

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public void setOverlap(int overlap) {
        this.overlap = overlap;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }
}
