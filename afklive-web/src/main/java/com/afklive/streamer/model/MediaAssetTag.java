package com.afklive.streamer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** ML-8: one tag on one library file. Rows go when either side is deleted (ON DELETE CASCADE). */
@Entity
@Table(name = "media_asset_tags")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MediaAssetTag {

    @EmbeddedId
    private Key id;

    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        @Column(name = "media_asset_id", nullable = false)
        private Long mediaAssetId;

        @Column(name = "tag_id", nullable = false)
        private Long tagId;
    }
}
