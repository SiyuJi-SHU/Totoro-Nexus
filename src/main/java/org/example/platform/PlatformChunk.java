package org.example.platform;

/** Exact location inside an immutable source version; index scores never imply answer confidence. */
public record PlatformChunk(String id,String documentId,String version,String datasetId,String sourceFile,
                            String title,int chunkIndex,int start,int end,String content,
                            Double vectorDistance,Double keywordScore,Double rerankScore,String method) {
    public PlatformChunk scored(Double distance,Double keyword,Double rerank,String via) {
        return new PlatformChunk(id,documentId,version,datasetId,sourceFile,title,chunkIndex,start,end,content,distance,keyword,rerank,via);
    }
    public String retrievalText() {
        String source=sourceFile==null?"":sourceFile.replace('\\','/');
        source=source.substring(source.lastIndexOf('/')+1);
        if(source.contains("__"))source=source.substring(source.lastIndexOf("__")+2);
        int extension=source.lastIndexOf('.');if(extension>0)source=source.substring(0,extension);
        String section=title==null?"":title.strip();
        String prefix="Document: "+clip(source,160)+(section.isBlank()?"":"\nSection: "+clip(section,320));
        return prefix+"\n\n"+content;
    }
    private static String clip(String value,int max){return value.length()<=max?value:value.substring(0,max);}
}
