package com.ruoyi.system.service.impl;

import cn.dev33.satoken.context.SaHolder;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ruoyi.common.core.constant.CacheConstants;
import com.ruoyi.common.core.constant.CacheNames;
import com.ruoyi.common.core.constant.UserConstants;
import com.ruoyi.common.mybatis.core.page.PageQuery;
import com.ruoyi.common.core.service.DictService;
import com.ruoyi.system.domain.SysDictData;
import com.ruoyi.system.domain.SysDictType;
import com.ruoyi.common.mybatis.core.page.TableDataInfo;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.StreamUtils;
import com.ruoyi.common.core.utils.StringUtils;
import com.ruoyi.common.redis.utils.CacheUtils;
import com.ruoyi.common.core.utils.SpringUtils;
import com.ruoyi.system.domain.bo.SysDictTypeBo;
import com.ruoyi.system.domain.vo.SysDictDataVo;
import com.ruoyi.system.domain.vo.SysDictTypeVo;
import com.ruoyi.system.mapper.SysDictDataMapper;
import com.ruoyi.system.mapper.SysDictTypeMapper;
import com.ruoyi.system.service.ISysDictTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 字典 业务层处理
 *
 * @author Lion Li
 */
@RequiredArgsConstructor
@Service
public class SysDictTypeServiceImpl implements ISysDictTypeService, DictService {

    private final SysDictTypeMapper baseMapper;
    private final SysDictDataMapper dictDataMapper;

    @Override
    public TableDataInfo<SysDictTypeVo> selectPageDictTypeList(SysDictTypeBo dictType, PageQuery pageQuery) {
        LambdaQueryWrapper<SysDictType> lqw = buildQueryWrapper(dictType);
        Page<SysDictTypeVo> page = baseMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(page);
    }

    /**
     * 根据条件分页查询字典类型
     *
     * @param dictType 字典类型信息
     * @return 字典类型集合信息
     */
    @Override
    public List<SysDictTypeVo> selectDictTypeList(SysDictTypeBo dictType) {
        LambdaQueryWrapper<SysDictType> lqw = buildQueryWrapper(dictType);
        return baseMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<SysDictType> buildQueryWrapper(SysDictTypeBo bo) {
        Map<String, Object> params = bo.getParams();
        LambdaQueryWrapper<SysDictType> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getDictName()), SysDictType::getDictName, bo.getDictName());
        lqw.like(StringUtils.isNotBlank(bo.getDictType()), SysDictType::getDictType, bo.getDictType());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), SysDictType::getStatus, bo.getStatus());
        lqw.between(params.get("beginTime") != null && params.get("endTime") != null,
            SysDictType::getCreateTime, params.get("beginTime"), params.get("endTime"));
        return lqw;
    }

    /**
     * 根据所有字典类型
     *
     * @return 字典类型集合信息
     */
    @Override
    public List<SysDictType> selectDictTypeAll() {
        return baseMapper.selectList();
    }

    /**
     * 根据字典类型查询字典数据
     *
     * @param dictType 字典类型
     * @return 字典数据集合信息
     */
    @Cacheable(cacheNames = CacheNames.SYS_DICT, key = "#dictType")
    @Override
    public List<SysDictDataVo> selectDictDataByType(String dictType) {
        List<SysDictDataVo> dictDatas = dictDataMapper.selectDictDataByType(dictType);
        if (CollUtil.isNotEmpty(dictDatas)) {
            return dictDatas;
        }
        return null;
    }

    /**
     * 根据字典类型ID查询信息
     *
     * @param dictId 字典类型ID
     * @return 字典类型
     */
    @Override
    public SysDictTypeVo selectDictTypeById(Long dictId) {
        return baseMapper.selectVoById(dictId);
    }

    /**
     * 根据字典类型查询信息
     *
     * @param dictType 字典类型
     * @return 字典类型
     */
    @Cacheable(cacheNames = CacheNames.SYS_DICT, key = "#dictType")
    @Override
    public SysDictTypeVo selectDictTypeByType(String dictType) {
        return baseMapper.selectVoById(new LambdaQueryWrapper<SysDictType>().eq(SysDictType::getDictType, dictType));
    }

    /**
     * 批量删除字典类型信息
     *
     * @param dictIds 需要删除的字典ID
     */
    @Override
    public void deleteDictTypeByIds(Long[] dictIds) {
        for (Long dictId : dictIds) {
            SysDictType dictType = baseMapper.selectById(dictId);
            if (dictDataMapper.exists(new LambdaQueryWrapper<SysDictData>()
                .eq(SysDictData::getDictType, dictType.getDictType()))) {
                throw new ServiceException(String.format("%1$s已分配,不能删除", dictType.getDictName()));
            }
            CacheUtils.evict(CacheNames.SYS_DICT, dictType.getDictType());
        }
        baseMapper.deleteBatchIds(Arrays.asList(dictIds));
    }

    /**
     * 加载字典缓存数据
     */
    @Override
    public void loadingDictCache() {
        List<SysDictDataVo> dictDataList = dictDataMapper.selectVoList(
            new LambdaQueryWrapper<SysDictData>().eq(SysDictData::getStatus, UserConstants.DICT_NORMAL));
        Map<String, List<SysDictDataVo>> dictDataMap = StreamUtils.groupByKey(dictDataList, SysDictDataVo::getDictType);
        dictDataMap.forEach((k,v) -> {
            List<SysDictDataVo> dictList = StreamUtils.sorted(v, Comparator.comparing(SysDictDataVo::getDictSort));
            CacheUtils.put(CacheNames.SYS_DICT, k, dictList);
        });
    }

    /**
     * 清空字典缓存数据
     */
    @Override
    public void clearDictCache() {
        CacheUtils.clear(CacheNames.SYS_DICT);
    }

    /**
     * 重置字典缓存数据
     */
    @Override
    public void resetDictCache() {
        clearDictCache();
        loadingDictCache();
    }

    /**
     * 新增保存字典类型信息
     *
     * @param bo 字典类型信息
     * @return 结果
     */
    @CachePut(cacheNames = CacheNames.SYS_DICT, key = "#bo.dictType")
    @Override
    public List<SysDictTypeVo> insertDictType(SysDictTypeBo bo) {
        SysDictType dict = BeanUtil.toBean(bo, SysDictType.class);
        int row = baseMapper.insert(dict);
        if (row > 0) {
            return new ArrayList<>();
        }
        throw new ServiceException("操作失败");
    }

    /**
     * 修改保存字典类型信息
     *
     * @param bo 字典类型信息
     * @return 结果
     */
    @CachePut(cacheNames = CacheNames.SYS_DICT, key = "#bo.dictType")
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<SysDictDataVo> updateDictType(SysDictTypeBo bo) {
        SysDictType dict = BeanUtil.toBean(bo, SysDictType.class);
        SysDictType oldDict = baseMapper.selectById(dict.getDictId());
        dictDataMapper.update(null, new LambdaUpdateWrapper<SysDictData>()
            .set(SysDictData::getDictType, dict.getDictType())
            .eq(SysDictData::getDictType, oldDict.getDictType()));
        int row = baseMapper.updateById(dict);
        if (row > 0) {
            CacheUtils.evict(CacheNames.SYS_DICT, oldDict.getDictType());
            return dictDataMapper.selectDictDataByType(dict.getDictType());
        }
        throw new ServiceException("操作失败");
    }

    /**
     * 校验字典类型称是否唯一
     *
     * @param dictType 字典类型
     * @return 结果
     */
    @Override
    public String checkDictTypeUnique(SysDictTypeBo dictType) {
        boolean exist = baseMapper.exists(new LambdaQueryWrapper<SysDictType>()
            .eq(SysDictType::getDictType, dictType.getDictType())
            .ne(ObjectUtil.isNotNull(dictType.getDictId()), SysDictType::getDictId, dictType.getDictId()));
        if (exist) {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    /**
     * 根据字典类型和字典值获取字典标签
     *
     * @param dictType  字典类型
     * @param dictValue 字典值
     * @param separator 分隔符
     * @return 字典标签
     */
    @SuppressWarnings("unchecked cast")
    @Override
    public String getDictLabel(String dictType, String dictValue, String separator) {
        // 优先从本地缓存获取
        List<SysDictDataVo> datas = (List<SysDictDataVo>) SaHolder.getStorage().get(CacheConstants.SYS_DICT_KEY + dictType);
        if (ObjectUtil.isNull(datas)) {
            datas = SpringUtils.getAopProxy(this).selectDictDataByType(dictType);
            SaHolder.getStorage().set(CacheConstants.SYS_DICT_KEY + dictType, datas);
        }

        Map<String, String> map = StreamUtils.toMap(datas, SysDictDataVo::getDictValue, SysDictDataVo::getDictLabel);
        if (StringUtils.containsAny(dictValue, separator)) {
            return Arrays.stream(dictValue.split(separator))
                .map(v -> map.getOrDefault(v, StringUtils.EMPTY))
                .collect(Collectors.joining(separator));
        } else {
            return map.getOrDefault(dictValue, StringUtils.EMPTY);
        }
    }

    /**
     * 根据字典类型和字典标签获取字典值
     *
     * @param dictType  字典类型
     * @param dictLabel 字典标签
     * @param separator 分隔符
     * @return 字典值
     */
    @SuppressWarnings("unchecked cast")
    @Override
    public String getDictValue(String dictType, String dictLabel, String separator) {
        // 优先从本地缓存获取
        List<SysDictDataVo> datas = (List<SysDictDataVo>) SaHolder.getStorage().get(CacheConstants.SYS_DICT_KEY + dictType);
        if (ObjectUtil.isNull(datas)) {
            datas = SpringUtils.getAopProxy(this).selectDictDataByType(dictType);
            SaHolder.getStorage().set(CacheConstants.SYS_DICT_KEY + dictType, datas);
        }

        Map<String, String> map = StreamUtils.toMap(datas, SysDictDataVo::getDictLabel, SysDictDataVo::getDictValue);
        if (StringUtils.containsAny(dictLabel, separator)) {
            return Arrays.stream(dictLabel.split(separator))
                .map(l -> map.getOrDefault(l, StringUtils.EMPTY))
                .collect(Collectors.joining(separator));
        } else {
            return map.getOrDefault(dictLabel, StringUtils.EMPTY);
        }
    }

}
