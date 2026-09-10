package com.example.wechatsales.channel;

import com.example.wechatsales.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户群「群成员 → 外部客户」归属解析（DESIGN.md §10 TODO 实现）。
 *
 * <p>会话存档中企业成员在客户群内的发言（from=成员 userid、roomid=群 chat_id）
 * 无法直接定位外部客户。本组件将群归属到「群内唯一的外部联系人」：</p>
 * <ol>
 *   <li>优先实时调用 {@code externalcontact/groupchat/get} 拉群成员（type=2 外部联系人），
 *       带内存缓存（默认 10 分钟），避免每条消息都打企微 API；</li>
 *   <li>实时拉取未启用/失败时回落静态配置 {@code app.wecom.room-external-members}；</li>
 *   <li>群内恰好 1 个外部成员 → 归属该客户；0 个或多个（无法唯一归属）→ empty，调用方跳过。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomMemberResolver {

    /** 群成员解析缓存 TTL（毫秒）：群成员变化低频，10 分钟足够 */
    static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private final AppProperties appProperties;
    private final WeComApiClient weComApiClient;

    /** chatId → 缓存的外部成员列表与写入时间 */
    private final Map<String, CachedMembers> cache = new ConcurrentHashMap<>();

    /**
     * 解析群内外部客户（仅当群内恰有 1 个外部成员时返回）。
     *
     * @param roomId 会话存档 roomid（客户群 chat_id）
     * @return 唯一外部客户 external_userid；无/多个/解析失败返回 empty
     */
    public Optional<String> resolveExternalUserId(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            return Optional.empty();
        }
        List<String> members = resolveMembers(roomId);
        if (members.size() == 1) {
            return Optional.of(members.get(0));
        }
        if (members.size() > 1) {
            log.info("[RoomMemberResolver] 群 {} 含 {} 个外部成员，无法唯一归属，跳过: {}",
                    roomId, members.size(), members);
        }
        return Optional.empty();
    }

    /** 群内外部成员列表：实时拉取（带缓存）→ 失败回落静态映射 */
    private List<String> resolveMembers(String roomId) {
        AppProperties.Wecom wecom = appProperties.getWecom();
        if (wecom.isRoomMemberLiveResolveEnabled() && weComApiClient.isConfigured()) {
            CachedMembers cached = cache.get(roomId);
            long now = System.currentTimeMillis();
            if (cached != null && now - cached.cachedAt < CACHE_TTL_MS) {
                return cached.members;
            }
            List<String> fetched = weComApiClient.fetchRoomExternalMembers(roomId);
            cache.put(roomId, new CachedMembers(fetched, now));
            if (!fetched.isEmpty()) {
                return fetched;
            }
            // 实时结果为空（失败/无外部成员）时仍尝试静态映射兜底
        }
        List<String> fallback = wecom.getRoomExternalMembers().get(roomId);
        return fallback == null ? List.of() : fallback;
    }

    private record CachedMembers(List<String> members, long cachedAt) {
    }
}
