package com.bootdo.oa.service.impl;

import com.bootdo.common.config.Constant;
import com.bootdo.common.service.DictService;
import com.bootdo.common.utils.DateUtils;
import com.bootdo.common.utils.PageUtils;
import com.bootdo.oa.dao.NotifyDao;
import com.bootdo.oa.dao.NotifyRecordDao;
import com.bootdo.oa.domain.NotifyDO;
import com.bootdo.oa.domain.NotifyDTO;
import com.bootdo.oa.domain.NotifyRecordDO;
import com.bootdo.oa.service.NotifyService;
import com.bootdo.system.dao.UserDao;
import com.bootdo.system.domain.UserDO;
import com.bootdo.system.service.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
public class NotifyServiceImpl implements NotifyService {
    @Autowired
    private NotifyDao notifyDao;
    @Autowired
    private NotifyRecordDao recordDao;
    @Autowired
    private UserDao userDao;
    @Autowired
    private DictService dictService;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private SimpMessagingTemplate template;

    @Override
    public NotifyDO get(Long id) {
        NotifyDO rDO = notifyDao.get(id);
        Long[] userIds = recordDao.getUserIdsByNotifyId(id);
        String[] userNames = userDao.getNamesByIds(userIds);
        if(rDO != null){
            rDO.setType(dictService.getName("oa_notify_type", rDO.getType()));
            rDO.setUserIds(userIds);
            rDO.setUserNames(String.join(",",userNames));
        }
        return rDO;
    }

    @Override
    public List<NotifyDO> list(Map<String, Object> map) {
        List<NotifyDO> notifys = notifyDao.list(map);
        for (NotifyDO notifyDO : notifys) {
            notifyDO.setType(dictService.getName("oa_notify_type", notifyDO.getType()));
        }
        return notifys;
    }

    @Override
    public int count(Map<String, Object> map) {
        return notifyDao.count(map);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int save(NotifyDO notify) {
        notify.setUpdateDate(new Date());
        int r = notifyDao.save(notify);
        addNotifyRecords(notify);
        //状态为“发布”则给在线用户发送通知
        if(Constant.NOTIFY_TYPE_PUBLIC.equals(notify.getStatus())) {
            sendNotification(notify.getUserIds(), "/queue/notifications", notify.getTitle());
        }
        return r;
    }

    @Transactional
    @Override
    public int update(NotifyDO notify) {
        int r = notifyDao.update(notify);
        recordDao.removeByNotifbyId(notify.getId());
        addNotifyRecords(notify);
        //状态为“发布”则给在线用户发送通知
        if(Constant.NOTIFY_TYPE_PUBLIC.equals(notify.getStatus())) {
            sendNotification(notify.getUserIds(), "/queue/notifications", notify.getTitle());
        }
        return r;
    }

    @Transactional
    @Override
    public int remove(Long id) {
        Long[] userIds = recordDao.getUserIdsByNotifyId(id);
        recordDao.removeByNotifbyId(id);
        int r = notifyDao.remove(id);
        //给在线用户发送通知
        sendNotification(userIds, "/queue/updateNotifications", null);
        return r;
    }

    @Transactional
    @Override
    public int batchRemove(Long[] ids) {
        Long[] userIds = recordDao.getUserIdsByNotifyIds(ids);
        recordDao.batchRemoveByNotifbyId(ids);
        int r = notifyDao.batchRemove(ids);
        //给在线用户发送通知
        sendNotification(userIds, "/queue/updateNotifications", null);
        return r;
    }

    @Override
    public PageUtils selfList(Map<String, Object> map) {
        List<NotifyDTO> rows = notifyDao.listDTO(map);
        for (NotifyDTO notifyDTO : rows) {
            notifyDTO.setBefore(DateUtils.getTimeBefore(notifyDTO.getUpdateDate()));
            notifyDTO.setSender(userDao.get(notifyDTO.getCreateBy()).getName());
        }
        PageUtils page = new PageUtils(rows, notifyDao.countDTO(map));
        return page;
    }

    private void addNotifyRecords(NotifyDO notify){
        // 保存到接受者列表中
        Long[] userIds = notify.getUserIds();
        Long notifyId = notify.getId();
        List<NotifyRecordDO> records = new ArrayList<>();
        for (Long userId : userIds) {
            NotifyRecordDO record = new NotifyRecordDO();
            record.setNotifyId(notifyId);
            record.setUserId(userId);
            record.setIsRead(0);
            records.add(record);
        }
        recordDao.batchSave(records);
    }

    @Override
    public void sendNotification(Long[] userIds, String destination, String message){
        //给在线用户发送通知
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1,1,0, TimeUnit.MILLISECONDS,new LinkedBlockingDeque<>());
        executor.execute(new Runnable() {
            @Override
            public void run() {
                for (UserDO userDO : sessionService.listOnlineUser()) {
                    for (Long userId : userIds) {
                        if (userId.equals(userDO.getUserId())) {
                            template.convertAndSendToUser(userDO.toString(), destination, "新消息：" + message);
                        }
                    }
                }
            }
        });
        executor.shutdown();
    }
}
