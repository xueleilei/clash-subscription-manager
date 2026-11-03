package dev.clashsub.sync;
import dev.clashsub.common.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
@Service
public class EmptyDecisionService {
    private final RefreshStateStore state;
    private final RefreshService refresh;
    public EmptyDecisionService(RefreshStateStore state, RefreshService refresh) { this.state = state; this.refresh = refresh; }
    @Transactional(readOnly = true) public List<EmptyDecisionView> pending(UUID owner) { return state.pending(owner); }
    @Transactional(propagation = Propagation.NEVER) public DecisionResult decide(UUID owner, UUID id, EmptyChoice choice) {
        if (choice == null) { throw ApiException.invalid("请选择保留旧数据或清空节点"); }
        if (choice == EmptyChoice.KEEP) { return state.keep(owner, id); }
        return new DecisionResult(id, "CLEARED", refresh.clear(owner, id));
    }
}
