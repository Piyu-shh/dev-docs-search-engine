package com.example.search.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VectorDto implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String id;
    private float[] embedding;
    private String text;
    private String url;
    private String title;
    private long timestamp;
}
