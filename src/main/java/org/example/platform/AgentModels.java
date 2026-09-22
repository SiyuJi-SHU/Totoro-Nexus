package org.example.platform;

import java.util.List;

/** Models verified against the existing DashScope text and tool-call protocol. */
public final class AgentModels {
    private AgentModels() {}
    public record Option(String id,String name) {}
    public static final List<Option> OPTIONS=List.of(
        new Option("deepseek-v4-flash","DeepSeek V4 Flash"),
        new Option("deepseek-v4-pro","DeepSeek V4 Pro"));

    public static String validate(String model) {
        if(model==null||model.isBlank())return null; // Existing versions retain the platform default.
        String selected=model.strip();
        if(OPTIONS.stream().noneMatch(option->option.id().equals(selected)))
            throw PlatformCatalog.bad("文本 LLM 不在可用列表中，请刷新 Console 后重新选择");
        return selected;
    }
    public static String resolve(PlatformModels.AgentConfig config,String defaultModel) {
        return config.chatModel()==null||config.chatModel().isBlank()?defaultModel:config.chatModel();
    }
}
